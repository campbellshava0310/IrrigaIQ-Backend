package com.irrigaiq.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.irrigaiq.telemetry.ReadingStore.Hourly;

/** API bodies of the telemetry module. */
public final class TelemetryViews {

	private TelemetryViews() {
	}

	/**
	 * @param online whether the device reported within {@link DeviceService#OFFLINE_AFTER}
	 * @param depletionPct for soil probes: the latest reading as root-zone depletion (0 = field capacity)
	 */
	public record DeviceView(UUID id, String name, String deviceId, SensorType type, DeviceStatus status, UUID fieldId,
			String fieldName, UUID assetId, String assetName, String keyPrefix, Instant keyRotatedAt,
			Instant lastSeenAt, boolean online, BigDecimal fieldCapacityPct, BigDecimal wiltingPointPct,
			Reading latest, Double depletionPct, Instant createdAt) {
	}

	/** Returned once, at registration or key rotation: the key is not stored and cannot be shown again. */
	public record IssuedKeyView(DeviceView device, String deviceKey) {
	}

	public record ReadingSeriesView(UUID deviceId, Instant from, Instant to, List<Hourly> hours) {
	}

	/** Receipt for a device: how many readings were new, and the server clock for devices without one. */
	public record IngestReceipt(int accepted, int duplicates, Instant serverTime) {
	}

}
