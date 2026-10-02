package com.irrigaiq.telemetry;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.irrigaiq.agronomy.SoilMoistureScale;
import com.irrigaiq.assets.Asset;
import com.irrigaiq.assets.AssetService;
import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.farm.Field;
import com.irrigaiq.farm.FieldService;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.telemetry.TelemetryViews.DeviceView;
import com.irrigaiq.telemetry.TelemetryViews.IssuedKeyView;
import com.irrigaiq.telemetry.TelemetryViews.ReadingSeriesView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registering and managing a farm's devices. Ingestion itself is {@link TelemetryService}. */
@Service
public class DeviceService {

	/** A device that has not reported for this long is shown as offline. */
	public static final Duration OFFLINE_AFTER = Duration.ofMinutes(30);

	static final int MAX_DEVICES_PER_FARM = 200;

	static final int MAX_SERIES_DAYS = 31;

	private final SensorRepository sensors;

	private final ReadingStore readings;

	private final FarmService farms;

	private final FieldService fields;

	private final AssetService assets;

	private final AuditService audit;

	private final Clock clock;

	public DeviceService(SensorRepository sensors, ReadingStore readings, FarmService farms, FieldService fields,
			AssetService assets, AuditService audit, Clock clock) {
		this.sensors = sensors;
		this.readings = readings;
		this.farms = farms;
		this.fields = fields;
		this.assets = assets;
		this.audit = audit;
		this.clock = clock;
	}

	public record DeviceDetails(SensorType type, String deviceId, String name, UUID fieldId, UUID assetId,
			BigDecimal fieldCapacityPct, BigDecimal wiltingPointPct) {
	}

	@Transactional(readOnly = true)
	public List<DeviceView> list(UUID farmId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		return views(farm, sensors.findByFarmIdOrderByNameAscDeviceIdAsc(farmId));
	}

	@Transactional(readOnly = true)
	public DeviceView get(UUID farmId, UUID deviceId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		return views(farm, List.of(require(farmId, deviceId))).get(0);
	}

	@Transactional
	public IssuedKeyView register(UUID farmId, AuthenticatedUser user, DeviceDetails details) {
		Farm farm = farms.requireAccessible(farmId, user);
		if (sensors.findByFarmIdOrderByNameAscDeviceIdAsc(farmId).size() >= MAX_DEVICES_PER_FARM) {
			throw ApiException.conflict(ErrorCode.OPERATION_NOT_ALLOWED,
					"A farm can have at most " + MAX_DEVICES_PER_FARM + " devices");
		}
		if (sensors.existsByFarmIdAndDeviceIdIgnoreCase(farmId, details.deviceId().strip())) {
			throw ApiException.conflict(ErrorCode.CONFLICT, "A device with this hardware id is already registered");
		}
		validate(farm, details);
		Sensor sensor = new Sensor(farm, details.type(), details.deviceId(), details.name());
		sensor.attach(details.fieldId(), details.assetId());
		sensor.calibrate(details.fieldCapacityPct(), details.wiltingPointPct());
		DeviceKeys.Issued key = DeviceKeys.issue();
		sensor.issueKey(key, clock.instant());
		sensors.saveAndFlush(sensor);
		audit.record(AuditEntry.of(AuditAction.DEVICE_REGISTERED)
			.target("DEVICE", sensor.getId())
			.detail("farmId", farmId)
			.detail("type", details.type().name())
			.detail("keyPrefix", key.prefix()));
		return new IssuedKeyView(views(farm, List.of(sensor)).get(0), key.value());
	}

	@Transactional
	public DeviceView update(UUID farmId, UUID deviceId, AuthenticatedUser user, DeviceDetails details,
			DeviceStatus status) {
		Farm farm = farms.requireAccessible(farmId, user);
		Sensor sensor = require(farmId, deviceId);
		if (details.type() != null && details.type() != sensor.getType()) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
					"A device's type cannot change; register a new device instead");
		}
		validate(farm, new DeviceDetails(sensor.getType(), sensor.getDeviceId(), details.name(), details.fieldId(),
				details.assetId(), details.fieldCapacityPct(), details.wiltingPointPct()));
		sensor.rename(details.name());
		sensor.attach(details.fieldId(), details.assetId());
		sensor.calibrate(details.fieldCapacityPct(), details.wiltingPointPct());
		if (status != null) {
			sensor.changeStatus(status);
		}
		sensors.flush();
		return views(farm, List.of(sensor)).get(0);
	}

	/** Issues a new key; the old one stops working immediately. */
	@Transactional
	public IssuedKeyView rotateKey(UUID farmId, UUID deviceId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		Sensor sensor = require(farmId, deviceId);
		DeviceKeys.Issued key = DeviceKeys.issue();
		sensor.issueKey(key, clock.instant());
		sensors.flush();
		audit.record(AuditEntry.of(AuditAction.DEVICE_KEY_ROTATED)
			.target("DEVICE", sensor.getId())
			.detail("keyPrefix", key.prefix()));
		return new IssuedKeyView(views(farm, List.of(sensor)).get(0), key.value());
	}

	@Transactional
	public void delete(UUID farmId, UUID deviceId, AuthenticatedUser user) {
		farms.requireAccessible(farmId, user);
		Sensor sensor = require(farmId, deviceId);
		sensors.delete(sensor); // readings cascade in the database
		audit.record(AuditEntry.of(AuditAction.DEVICE_DELETED)
			.target("DEVICE", deviceId)
			.detail("deviceId", sensor.getDeviceId()));
	}

	@Transactional(readOnly = true)
	public ReadingSeriesView readings(UUID farmId, UUID deviceId, AuthenticatedUser user, int hours) {
		farms.requireAccessible(farmId, user);
		Sensor sensor = require(farmId, deviceId);
		int window = Math.max(1, Math.min(hours, MAX_SERIES_DAYS * 24));
		Instant to = clock.instant();
		Instant from = to.minus(Duration.ofHours(window));
		return new ReadingSeriesView(sensor.getId(), from, to, readings.hourly(sensor.getId(), from, to));
	}

	// ---- Module API (farm already authorised) ----

	@Transactional(readOnly = true)
	public List<Sensor> devices(Farm farm, SensorType type) {
		return sensors.findByFarmIdAndType(farm.getId(), type);
	}

	/** Depletion for a probe reading, using the probe's calibration (defaults when uncalibrated). */
	public static double depletionPct(Sensor probe, double readingPct) {
		return SoilMoistureScale.depletionPct(readingPct,
				(probe.getFieldCapacityPct() != null) ? probe.getFieldCapacityPct().doubleValue() : null,
				(probe.getWiltingPointPct() != null) ? probe.getWiltingPointPct().doubleValue() : null);
	}

	private List<DeviceView> views(Farm farm, List<Sensor> devices) {
		Map<UUID, String> fieldNames = fields.fieldsOf(farm.getId())
			.stream()
			.collect(Collectors.toMap(Field::getId, Field::getName));
		Map<UUID, String> assetNames = assets.assetsOf(farm.getId())
			.stream()
			.collect(Collectors.toMap(Asset::getId, Asset::getName));
		Instant onlineSince = clock.instant().minus(OFFLINE_AFTER);
		return devices.stream().map((sensor) -> {
			Reading latest = readings.latest(sensor.getId()).orElse(null);
			Double depletion = (sensor.getType() == SensorType.SOIL_MOISTURE && latest != null
					&& latest.soilMoisturePct() != null)
							? Math.round(depletionPct(sensor, latest.soilMoisturePct()) * 10) / 10.0 : null;
			return new DeviceView(sensor.getId(), sensor.displayName(), sensor.getDeviceId(), sensor.getType(),
					sensor.getStatus(), sensor.getFieldId(), fieldNames.get(sensor.getFieldId()), sensor.getAssetId(),
					assetNames.get(sensor.getAssetId()), sensor.getKeyPrefix(), sensor.getKeyRotatedAt(),
					sensor.getLastSeenAt(), sensor.getLastSeenAt() != null && sensor.getLastSeenAt().isAfter(onlineSince),
					sensor.getFieldCapacityPct(), sensor.getWiltingPointPct(), latest, depletion,
					sensor.getCreatedAt());
		}).toList();
	}

	private Sensor require(UUID farmId, UUID deviceId) {
		return sensors.findByIdAndFarmId(deviceId, farmId).orElseThrow(() -> ApiException.notFound("Device"));
	}

	private void validate(Farm farm, DeviceDetails details) {
		if (details.fieldId() != null && fields.findInFarm(farm.getId(), details.fieldId()).isEmpty()) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "The field does not belong to this farm");
		}
		if (details.assetId() != null && assets.findInFarm(farm.getId(), details.assetId()).isEmpty()) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "The asset does not belong to this farm");
		}
		switch (details.type()) {
			case SOIL_MOISTURE -> {
				if (details.fieldId() == null) {
					throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "A soil probe must be placed in a field");
				}
			}
			case PUMP_MONITOR -> {
				if (details.assetId() == null) {
					throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "A pump monitor must be fitted to a pump");
				}
			}
			case FLOW_METER -> {
				if (details.assetId() == null && details.fieldId() == null) {
					throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
							"A water meter must be on an asset (pump or line) or a field");
				}
			}
			default -> {
			}
		}
		boolean hasFc = details.fieldCapacityPct() != null;
		boolean hasWp = details.wiltingPointPct() != null;
		if (hasFc != hasWp || (hasFc && details.fieldCapacityPct().compareTo(details.wiltingPointPct()) <= 0)) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
					"Calibration needs both readings, with field capacity above wilting point");
		}
	}

}
