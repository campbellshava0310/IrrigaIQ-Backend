package com.irrigaiq.agronomy;

import java.util.EnumMap;
import java.util.Map;

import com.irrigaiq.farm.CropType;

/**
 * Crop parameters. Kc values, stage lengths, root depths and p come from FAO Irrigation and
 * Drainage Paper 56 (Tables 11, 12 and 22); Ky from FAO Paper 33. Stage lengths are regional
 * defaults and should be tuned per variety with local agronomists.
 */
public final class CropCatalog {

	private static final Map<CropType, CropParameters> PARAMETERS = new EnumMap<>(CropType.class);

	static {
		PARAMETERS.put(CropType.TOMATO,
				new CropParameters(0.60, 1.15, 0.70, new int[] { 30, 40, 40, 25 }, 0.25, 0.80, 0.40, 1.05, 50));
		PARAMETERS.put(CropType.MAIZE,
				new CropParameters(0.30, 1.20, 0.35, new int[] { 20, 35, 40, 30 }, 0.30, 1.00, 0.55, 1.25, 8));
		PARAMETERS.put(CropType.WHEAT,
				new CropParameters(0.30, 1.15, 0.25, new int[] { 15, 30, 65, 40 }, 0.30, 1.00, 0.55, 1.15, 6));
		PARAMETERS.put(CropType.SOYBEAN,
				new CropParameters(0.40, 1.15, 0.50, new int[] { 20, 30, 35, 25 }, 0.30, 0.80, 0.50, 0.85, 3));
		PARAMETERS.put(CropType.POTATO,
				new CropParameters(0.50, 1.15, 0.75, new int[] { 25, 30, 45, 30 }, 0.25, 0.50, 0.35, 1.10, 35));
		// Not tabulated in FAO-56; conservative values pending local calibration.
		PARAMETERS.put(CropType.TOBACCO,
				new CropParameters(0.50, 1.10, 0.80, new int[] { 20, 30, 40, 30 }, 0.30, 0.80, 0.50, 0.90, 2.8));
	}

	private CropCatalog() {
	}

	public static CropParameters parameters(CropType cropType) {
		return PARAMETERS.get(cropType);
	}

}
