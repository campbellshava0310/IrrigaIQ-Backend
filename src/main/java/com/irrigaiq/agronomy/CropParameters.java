package com.irrigaiq.agronomy;

/**
 * FAO-56 single crop coefficient parameters for one crop.
 *
 * @param kcIni crop coefficient during the initial stage
 * @param kcMid crop coefficient at full cover
 * @param kcEnd crop coefficient at harvest
 * @param stageDays lengths of the initial, development, mid-season and late-season stages
 * @param rootDepthMinM effective root depth at planting (m)
 * @param rootDepthMaxM effective root depth from mid-season onwards (m)
 * @param depletionFraction FAO-56 p: fraction of TAW that can be depleted before stress (ETc 5 mm/d)
 * @param yieldResponseKy FAO-33 Ky: relative yield loss per unit of relative ET deficit
 * @param typicalYieldTHa attainable yield under good management, used when none is set
 */
public record CropParameters(double kcIni, double kcMid, double kcEnd, int[] stageDays, double rootDepthMinM,
		double rootDepthMaxM, double depletionFraction, double yieldResponseKy, double typicalYieldTHa) {

	public int seasonLengthDays() {
		return stageDays[0] + stageDays[1] + stageDays[2] + stageDays[3];
	}

	public GrowthPhase phaseAt(int daysAfterPlanting) {
		if (daysAfterPlanting < stageDays[0]) {
			return GrowthPhase.INITIAL;
		}
		if (daysAfterPlanting < stageDays[0] + stageDays[1]) {
			return GrowthPhase.DEVELOPMENT;
		}
		if (daysAfterPlanting < stageDays[0] + stageDays[1] + stageDays[2]) {
			return GrowthPhase.MID_SEASON;
		}
		if (daysAfterPlanting < seasonLengthDays()) {
			return GrowthPhase.LATE_SEASON;
		}
		return GrowthPhase.HARVEST;
	}

	/** Kc curve (FAO-56 Fig. 25): constant, linear rise, plateau, linear decline. */
	public double kcAt(int daysAfterPlanting) {
		int ini = stageDays[0];
		int dev = stageDays[1];
		int mid = stageDays[2];
		int late = stageDays[3];
		if (daysAfterPlanting < ini) {
			return kcIni;
		}
		if (daysAfterPlanting < ini + dev) {
			return kcIni + (kcMid - kcIni) * (daysAfterPlanting - ini) / dev;
		}
		if (daysAfterPlanting < ini + dev + mid) {
			return kcMid;
		}
		if (daysAfterPlanting < ini + dev + mid + late) {
			return kcMid + (kcEnd - kcMid) * (daysAfterPlanting - ini - dev - mid) / late;
		}
		return BARE_SOIL_KC;
	}

	/** Roots deepen linearly until full cover (start of mid-season). */
	public double rootDepthAt(int daysAfterPlanting) {
		double growthDays = stageDays[0] + stageDays[1];
		double fraction = Math.min(1.0, Math.max(0.0, daysAfterPlanting / growthDays));
		return rootDepthMinM + (rootDepthMaxM - rootDepthMinM) * fraction;
	}

	/** Kc of bare soil after harvest (evaporation only). */
	public static final double BARE_SOIL_KC = 0.2;

}
