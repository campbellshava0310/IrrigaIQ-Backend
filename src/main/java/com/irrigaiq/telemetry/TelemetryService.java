package com.irrigaiq.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.farm.Field;
import com.irrigaiq.farm.FieldService;
import com.irrigaiq.irrigation.IrrigationService;
import com.irrigaiq.telemetry.TelemetryViews.IngestReceipt;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts readings from devices. Each device authenticates with its own key; readings are stored
 * idempotently, soil-probe readings recalibrate the field's water balance, and the farm is
 * re-evaluated (alerts, anomaly checks) shortly after.
 */
@Service
public class TelemetryService {

	private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

	static final int MAX_READINGS_PER_REQUEST = 500;

	static final int MAX_REQUESTS_PER_MINUTE = 120;

	/** Devices without a real-time clock drift; anything further ahead than this is rejected. */
	static final Duration MAX_CLOCK_AHEAD = Duration.ofMinutes(5);

	/** Buffered readings (a device that was offline) are accepted this far back. */
	static final Duration MAX_BACKFILL = Duration.ofDays(60);

	/** Probe readings older than this only go into the history, not into the water balance. */
	static final Duration PROBE_CALIBRATION_WINDOW = Duration.ofDays(30);

	private final SensorRepository sensors;

	private final ReadingStore store;

	private final FieldService fields;

	private final IrrigationService irrigation;

	private final FarmRefreshDebouncer refresh;

	private final Clock clock;

	private final Map<UUID, long[]> requestWindows = new ConcurrentHashMap<>();

	public TelemetryService(SensorRepository sensors, ReadingStore store, FieldService fields,
			IrrigationService irrigation, FarmRefreshDebouncer refresh, Clock clock) {
		this.sensors = sensors;
		this.store = store;
		this.fields = fields;
		this.irrigation = irrigation;
		this.refresh = refresh;
		this.clock = clock;
	}

	@Transactional
	public IngestReceipt ingest(String deviceKey, ReadingSource source, List<Reading> batch) {
		Sensor sensor = authenticate(deviceKey);
		if (sensor.getStatus() == DeviceStatus.DISABLED) {
			throw ApiException.forbidden(ErrorCode.FORBIDDEN, "This device is disabled");
		}
		throttle(sensor.getId());
		if (source == ReadingSource.MANUAL) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "Devices report DEVICE or SIMULATED readings");
		}
		Instant now = clock.instant();
		List<Reading> readings = normalise(batch, now);
		int accepted = store.insert(sensor.getId(), source, readings, now);
		sensor.seen(now);

		if (accepted > 0) {
			if (sensor.getType() == SensorType.SOIL_MOISTURE && sensor.getFieldId() != null) {
				calibrateField(sensor, readings, now);
			}
			refresh.request(sensor.getFarm().getId());
		}
		return new IngestReceipt(accepted, readings.size() - accepted, now);
	}

	private Sensor authenticate(String deviceKey) {
		String prefix = DeviceKeys.prefixOf(deviceKey).orElse(null);
		Sensor sensor = (prefix != null) ? sensors.findByKeyPrefix(prefix).orElse(null) : null;
		// Hash even when the prefix is unknown, so timing does not reveal which prefixes exist.
		boolean valid = DeviceKeys.matches((deviceKey != null) ? deviceKey : "",
				(sensor != null) ? sensor.getKeyHash() : null);
		if (!valid) {
			throw ApiException.unauthorized(ErrorCode.UNAUTHORIZED, "Missing or unknown device key");
		}
		return sensor;
	}

	/** Fixed one-minute window per device: enough for any sane reporting rate, stops a runaway loop. */
	private void throttle(UUID sensorId) {
		long minute = clock.millis() / 60_000;
		long[] window = requestWindows.compute(sensorId,
				(id, current) -> (current == null || current[0] != minute) ? new long[] { minute, 1 }
						: new long[] { minute, current[1] + 1 });
		if (window[1] > MAX_REQUESTS_PER_MINUTE) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS,
					"Too many requests from this device; send readings in batches");
		}
		if (requestWindows.size() > 10_000) {
			requestWindows.values().removeIf((w) -> w[0] < minute);
		}
	}

	private static List<Reading> normalise(List<Reading> batch, Instant now) {
		if (batch == null || batch.isEmpty()) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "Send at least one reading");
		}
		if (batch.size() > MAX_READINGS_PER_REQUEST) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
					"At most " + MAX_READINGS_PER_REQUEST + " readings per request");
		}
		List<Reading> out = new ArrayList<>(batch.size());
		for (Reading r : batch) {
			if (r == null || r.isEmpty()) {
				throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "Every reading needs at least one value");
			}
			Instant at = (r.recordedAt() != null) ? r.recordedAt().truncatedTo(ChronoUnit.MILLIS)
					: now.truncatedTo(ChronoUnit.MILLIS);
			if (at.isAfter(now.plus(MAX_CLOCK_AHEAD))) {
				throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
						"A reading is timestamped in the future; check the device clock");
			}
			if (at.isBefore(now.minus(MAX_BACKFILL))) {
				throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "Readings older than 60 days are not accepted");
			}
			out.add(new Reading(at, r.soilMoisturePct(), r.temperatureC(), r.humidityPct(), r.rainfallMm(),
					r.flowLpm(), r.volumeLitres(), r.pressureBar(), r.powerKw(), r.vibrationMmS(), r.motorTempC()));
		}
		return out;
	}

	/** The latest probe reading of each day becomes that day's sensor observation for the field. */
	private void calibrateField(Sensor probe, List<Reading> readings, Instant now) {
		Field field = fields.findInFarm(probe.getFarm().getId(), probe.getFieldId()).orElse(null);
		if (field == null) {
			return;
		}
		ZoneId zone = field.getFarm().zone();
		Map<LocalDate, Reading> latestPerDay = readings.stream()
			.filter((r) -> r.soilMoisturePct() != null)
			.filter((r) -> r.recordedAt().isAfter(now.minus(PROBE_CALIBRATION_WINDOW)))
			.collect(Collectors.toMap((r) -> LocalDate.ofInstant(r.recordedAt(), zone), (r) -> r,
					(a, b) -> a.recordedAt().isAfter(b.recordedAt()) ? a : b));
		latestPerDay.values()
			.stream()
			.filter(Objects::nonNull)
			.sorted(Comparator.comparing(Reading::recordedAt))
			.forEach((r) -> {
				double depletion = DeviceService.depletionPct(probe, r.soilMoisturePct());
				irrigation.recordProbeReading(field, r.recordedAt(), depletion, note(probe, r.soilMoisturePct()));
			});
		if (!latestPerDay.isEmpty()) {
			log.debug("Probe {} calibrated field {} on {} day(s)", probe.getId(), field.getId(), latestPerDay.size());
		}
	}

	private static String note(Sensor probe, double readingPct) {
		boolean calibrated = probe.getFieldCapacityPct() != null;
		return String.format(Locale.ENGLISH, "Probe %s read %.1f %% (%s)", probe.displayName(), readingPct,
				calibrated ? "calibrated" : "default calibration");
	}

}
