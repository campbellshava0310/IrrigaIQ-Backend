package com.irrigaiq.ml;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.irrigaiq.assets.Asset;
import com.irrigaiq.assets.AssetService;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.farm.Field;
import com.irrigaiq.farm.FieldService;
import com.irrigaiq.irrigation.IrrigationEvent;
import com.irrigaiq.irrigation.IrrigationService;
import com.irrigaiq.irrigation.IrrigationStatus;
import com.irrigaiq.ml.MlApi.Anomaly;
import com.irrigaiq.ml.MlApi.FlowCheck;
import com.irrigaiq.ml.MlApi.FlowPoint;
import com.irrigaiq.ml.MlApi.WaterAnomalyResult;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.telemetry.DeviceService;
import com.irrigaiq.telemetry.DeviceStatus;
import com.irrigaiq.telemetry.Reading;
import com.irrigaiq.telemetry.ReadingStore;
import com.irrigaiq.telemetry.ReadingStore.Hourly;
import com.irrigaiq.telemetry.Sensor;
import com.irrigaiq.telemetry.SensorType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Water-meter anomaly checks. Hourly flow from each meter is compared with that meter's own
 * history by the ML service, which knows which hours were scheduled irrigation (flow expected) and
 * which were not (flow suspicious). Findings are worded as possibilities, never as a confirmed leak.
 */
@Service
public class WaterAnomalyService {

	static final Duration HISTORY = Duration.ofDays(21);

	/** The window judged for an anomaly; the hours before it are the baseline. */
	static final Duration RECENT = Duration.ofHours(6);

	/** A meter silent for this long is reported as not reporting, not checked. */
	static final Duration STALE = Duration.ofHours(3);

	static final int CHART_HOURS = 48;

	private final FarmService farms;

	private final DeviceService devices;

	private final ReadingStore readings;

	private final IrrigationService irrigation;

	private final FieldService fields;

	private final AssetService assets;

	private final MlClient ml;

	private final Clock clock;

	/** Last result per meter, reused while its hourly input is unchanged. */
	private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

	private record Cached(int inputHash, WaterAnomalyResult result) {
	}

	public WaterAnomalyService(FarmService farms, DeviceService devices, ReadingStore readings,
			IrrigationService irrigation, FieldService fields, AssetService assets, MlClient ml, Clock clock) {
		this.farms = farms;
		this.devices = devices;
		this.readings = readings;
		this.irrigation = irrigation;
		this.fields = fields;
		this.assets = assets;
		this.ml = ml;
		this.clock = clock;
	}

	public record HourPoint(Instant hour, double observedLitres, Double expectedLitres, boolean scheduled,
			boolean anomalous) {
	}

	/**
	 * @param status {@code OK}, {@code ANOMALY}, {@code INSUFFICIENT_HISTORY}, {@code NOT_REPORTING}
	 * or {@code UNAVAILABLE} (ML service offline)
	 * @param hours the last {@value #CHART_HOURS} hours, observed against expected
	 */
	public record MeterCheck(UUID deviceId, String meterName, UUID assetId, String assetName, UUID fieldId,
			String fieldName, String status, Anomaly anomaly, List<HourPoint> hours, Instant lastReadingAt,
			String modelVersion) {
	}

	public record WaterAnomalyView(boolean available, Instant checkedAt, List<MeterCheck> meters) {
	}

	@Transactional(readOnly = true)
	public WaterAnomalyView check(UUID farmId, AuthenticatedUser user) {
		return check(farms.requireAccessible(farmId, user));
	}

	@Transactional(readOnly = true)
	public WaterAnomalyView check(Farm farm) {
		List<Sensor> meters = devices.devices(farm, SensorType.FLOW_METER)
			.stream()
			.filter((meter) -> meter.getStatus() == DeviceStatus.ACTIVE)
			.toList();
		if (meters.isEmpty()) {
			return new WaterAnomalyView(ml.available(), clock.instant(), List.of());
		}
		Instant now = clock.instant();
		Instant from = now.minus(HISTORY);
		ZoneId zone = farm.zone();
		Map<UUID, Asset> farmAssets = assets.assetsOf(farm.getId())
			.stream()
			.collect(Collectors.toMap(Asset::getId, (a) -> a));
		Map<UUID, Field> farmFields = fields.fieldsOf(farm.getId())
			.stream()
			.collect(Collectors.toMap(Field::getId, (f) -> f));
		List<IrrigationEvent> events = irrigation.eventsBetween(farm, from.minus(Duration.ofDays(1)), now)
			.stream()
			.filter((e) -> e.getStatus() == IrrigationStatus.COMPLETED || e.getStatus() == IrrigationStatus.PLANNED)
			.toList();

		boolean anyCalled = false;
		boolean anyAnswered = false;
		List<MeterCheck> checks = new ArrayList<>();
		for (Sensor meter : meters) {
			Set<UUID> served = servedFields(meter, farmAssets, farmFields);
			List<IrrigationEvent> meterEvents = events.stream()
				.filter((e) -> served.contains(e.getField().getId()))
				.toList();
			List<Hourly> hourly = readings.hourly(meter.getId(), from, now)
				.stream()
				.filter((h) -> h.litresPerHour() != null)
				.toList();
			Asset asset = farmAssets.get(meter.getAssetId());
			Field field = farmFields.get(meter.getFieldId());
			// The actual latest reading, not the start of its hourly bucket.
			Instant last = hourly.isEmpty() ? null
					: readings.latest(meter.getId()).map(Reading::recordedAt).orElse(null);

			List<FlowPoint> points = hourly.stream()
				.map((h) -> new FlowPoint(h.hour().atZone(zone).toOffsetDateTime(), h.litresPerHour(),
						scheduled(h.hour(), meterEvents)))
				.toList();
			String status;
			WaterAnomalyResult result = null;
			if (last == null || last.isBefore(now.minus(STALE))) {
				status = "NOT_REPORTING";
			}
			else {
				anyCalled = true;
				Instant recentFrom = now.minus(RECENT);
				List<FlowPoint> history = points.stream().filter((p) -> p.at().toInstant().isBefore(recentFrom)).toList();
				List<FlowPoint> recent = points.stream().filter((p) -> !p.at().toInstant().isBefore(recentFrom)).toList();
				result = detect(meter, history, recent);
				if (result == null) {
					status = "UNAVAILABLE";
				}
				else {
					anyAnswered = true;
					status = "INSUFFICIENT_HISTORY".equals(result.status()) ? "INSUFFICIENT_HISTORY"
							: (result.anomaly() != null) ? "ANOMALY" : "OK";
				}
			}
			checks.add(new MeterCheck(meter.getId(), meter.displayName(), meter.getAssetId(),
					(asset != null) ? asset.getName() : null, meter.getFieldId(), (field != null) ? field.getName() : null,
					status, (result != null) ? result.anomaly() : null, chart(points, result), last,
					(result != null) ? result.modelVersion() : null));
		}
		return new WaterAnomalyView(!anyCalled || anyAnswered, now, checks);
	}

	private WaterAnomalyResult detect(Sensor meter, List<FlowPoint> history, List<FlowPoint> recent) {
		if (recent.isEmpty()) {
			return null;
		}
		int hash = List.of(history, recent).hashCode();
		Cached cached = cache.get(meter.getId());
		if (cached != null && cached.inputHash() == hash) {
			return cached.result();
		}
		WaterAnomalyResult result = ml.waterAnomaly(meter.getId().toString(), history, recent).orElse(null);
		if (result != null) {
			cache.put(meter.getId(), new Cached(hash, result));
		}
		return result;
	}

	/** Observed flow for the last hours, with the model's expectation for the hours it judged. */
	private static List<HourPoint> chart(List<FlowPoint> points, WaterAnomalyResult result) {
		Map<Instant, FlowCheck> judged = (result != null && result.points() != null) ? result.points()
			.stream()
			.collect(Collectors.toMap((p) -> p.at().toInstant(), (p) -> p, (a, b) -> b)) : Map.of();
		int start = Math.max(0, points.size() - CHART_HOURS);
		return points.subList(start, points.size()).stream().map((p) -> {
			FlowCheck check = judged.get(p.at().toInstant());
			return new HourPoint(p.at().toInstant(), Math.round(p.litres() * 10) / 10.0,
					(check != null) ? check.expectedLitres() : null, p.scheduled(), check != null && check.anomalous());
		}).toList();
	}

	/**
	 * Fields a meter's water can reach: its own field, and every field supplied by the asset it sits
	 * on or by any asset upstream of it (a meter on a mainline sees the pump feeding that line).
	 */
	static Set<UUID> servedFields(Sensor meter, Map<UUID, Asset> assets, Map<UUID, Field> fields) {
		Set<UUID> served = new HashSet<>();
		if (meter.getFieldId() != null) {
			served.add(meter.getFieldId());
		}
		Set<UUID> chain = new HashSet<>();
		UUID cursor = meter.getAssetId();
		for (int depth = 0; cursor != null && depth < 20 && chain.add(cursor); depth++) {
			Asset asset = assets.get(cursor);
			if (asset == null) {
				break;
			}
			if (asset.getFieldId() != null) {
				served.add(asset.getFieldId());
			}
			cursor = asset.getParentId();
		}
		for (Field field : fields.values()) {
			if (field.getPumpAssetId() != null && chain.contains(field.getPumpAssetId())) {
				served.add(field.getId());
			}
		}
		return served;
	}

	private static boolean scheduled(Instant hour, List<IrrigationEvent> events) {
		Instant end = hour.plus(Duration.ofHours(1));
		for (IrrigationEvent event : events) {
			Instant start = event.getStartedAt();
			Instant stop = start.plus(Duration.ofMinutes(event.getDurationMinutes()));
			if (start.isBefore(end) && stop.isAfter(hour)) {
				return true;
			}
		}
		return false;
	}

}
