package com.irrigaiq.farm;

/**
 * Irrigation system types with typical application efficiency, gross application rate and the
 * largest net depth one application should put down. Overhead systems that apply more than the
 * soil can take in during a pass lose the excess to runoff and deep drainage, so large deficits are
 * refilled over several passes. Farms override efficiency and rate per field once commissioning
 * measurements exist.
 */
public enum IrrigationMethod {

	/** Refills the wetted root zone in one run; no practical per-application limit. */
	DRIP(0.90, 3.0, Double.POSITIVE_INFINITY),

	MICRO_SPRINKLER(0.85, 5.0, 35.0),

	SPRINKLER(0.75, 8.0, 35.0),

	/** Field-average rate: one revolution applies a small depth over many hours. */
	CENTER_PIVOT(0.85, 1.0, 20.0),

	FURROW(0.60, 12.0, 80.0),

	FLOOD(0.50, 20.0, 100.0);

	private final double defaultEfficiency;

	private final double defaultApplicationRateMmH;

	private final double maxNetApplicationMm;

	IrrigationMethod(double defaultEfficiency, double defaultApplicationRateMmH, double maxNetApplicationMm) {
		this.defaultEfficiency = defaultEfficiency;
		this.defaultApplicationRateMmH = defaultApplicationRateMmH;
		this.maxNetApplicationMm = maxNetApplicationMm;
	}

	/** Net depth one application can deliver without runoff or deep drainage. */
	public double maxNetApplicationMm() {
		return maxNetApplicationMm;
	}

	public double defaultEfficiency() {
		return defaultEfficiency;
	}

	public double defaultApplicationRateMmH() {
		return defaultApplicationRateMmH;
	}

	/** Used for fields whose irrigation system has not been configured. */
	public static final IrrigationMethod DEFAULT = DRIP;

}
