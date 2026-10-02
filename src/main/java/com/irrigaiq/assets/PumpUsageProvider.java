package com.irrigaiq.assets;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Pump run time derived from irrigation records. Implemented by the irrigation module so the
 * infrastructure module can forecast run-hour based services without depending on it.
 */
public interface PumpUsageProvider {

	/** Completed run-hours per pump asset id since {@code since}. */
	Map<UUID, Double> runHoursSince(UUID farmId, Instant since);

}
