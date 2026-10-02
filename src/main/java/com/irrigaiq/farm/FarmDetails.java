package com.irrigaiq.farm;

import java.math.BigDecimal;

/**
 * Editable farm attributes. Optional operational settings fall back to regional defaults.
 *
 * @param baselineIntervalDays irrigation interval of the conventional calendar schedule that water
 * savings are measured against
 * @param baselineDepthMm gross depth applied per irrigation in that conventional schedule
 */
public record FarmDetails(String name, String location, BigDecimal areaHa, BigDecimal latitude, BigDecimal longitude,
		String timezone, String currency, BigDecimal electricityTariff, Integer baselineIntervalDays,
		BigDecimal baselineDepthMm) {

	public static FarmDetails basic(String name, String location, BigDecimal areaHa) {
		return new FarmDetails(name, location, areaHa, null, null, null, null, null, null, null);
	}

}
