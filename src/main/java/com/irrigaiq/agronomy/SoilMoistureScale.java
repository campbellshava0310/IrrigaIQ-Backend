package com.irrigaiq.agronomy;

/**
 * Converts between a soil-moisture reading (percent, on a probe's own scale) and root-zone
 * depletion (percent of available water used: 0 = field capacity, 100 = wilting point).
 *
 * <p>Low-cost capacitive probes are not volumetric instruments: their percentage depends on the
 * probe and the soil. Growers therefore calibrate in place, noting the reading a day after a full
 * irrigation has drained (field capacity) and the reading at which the crop wilts. Between the two
 * points the scale is treated as linear, which is the usual first-order approximation.
 *
 * <p>The defaults describe an uncalibrated probe and are also the scale the irrigation-need model
 * expects: on it the model's learnt "dry" threshold (about 25 %) falls at 60 % depletion, the
 * stress threshold of typical field crops (FAO-56 Table 22, p = 0.5-0.6).
 */
public final class SoilMoistureScale {

	public static final double DEFAULT_FIELD_CAPACITY_PCT = 40.0;

	public static final double DEFAULT_WILTING_POINT_PCT = 15.0;

	private SoilMoistureScale() {
	}

	/** Depletion for a reading, clamped to 0-100 (wetter than field capacity counts as full). */
	public static double depletionPct(double readingPct, Double fieldCapacityPct, Double wiltingPointPct) {
		double fc = (fieldCapacityPct != null) ? fieldCapacityPct : DEFAULT_FIELD_CAPACITY_PCT;
		double wp = (wiltingPointPct != null) ? wiltingPointPct : DEFAULT_WILTING_POINT_PCT;
		if (fc <= wp) {
			throw new IllegalArgumentException("Field capacity must be above wilting point");
		}
		return clamp(100 * (fc - readingPct) / (fc - wp));
	}

	/** The reading an uncalibrated (default-scale) probe would show at a given depletion. */
	public static double defaultScaleReading(double depletionPct) {
		double used = clamp(depletionPct) / 100;
		return DEFAULT_FIELD_CAPACITY_PCT - used * (DEFAULT_FIELD_CAPACITY_PCT - DEFAULT_WILTING_POINT_PCT);
	}

	private static double clamp(double pct) {
		return Math.max(0, Math.min(100, pct));
	}

}
