package com.irrigaiq.agronomy;

import java.util.EnumMap;
import java.util.Map;

import com.irrigaiq.farm.SoilType;

/**
 * Available water (field capacity minus wilting point) per metre of soil, mid-points of the
 * FAO-56 Table 19 ranges. Classes the table omits use neighbouring textures.
 */
public final class SoilCatalog {

	private static final Map<SoilType, Double> AVAILABLE_WATER_MM_PER_M = new EnumMap<>(SoilType.class);

	static {
		AVAILABLE_WATER_MM_PER_M.put(SoilType.SAND, 65.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.LOAMY_SAND, 100.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.SANDY_LOAM, 125.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.LOAM, 155.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.SILT_LOAM, 175.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.SILT, 185.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.SANDY_CLAY_LOAM, 140.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.CLAY_LOAM, 160.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.SILTY_CLAY_LOAM, 180.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.SANDY_CLAY, 135.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.SILTY_CLAY, 170.0);
		AVAILABLE_WATER_MM_PER_M.put(SoilType.CLAY, 130.0);
	}

	/** Used when a field has no soil texture recorded. */
	public static final SoilType DEFAULT_SOIL = SoilType.LOAM;

	private SoilCatalog() {
	}

	public static double availableWaterMmPerM(SoilType soilType) {
		return AVAILABLE_WATER_MM_PER_M.get((soilType != null) ? soilType : DEFAULT_SOIL);
	}

}
