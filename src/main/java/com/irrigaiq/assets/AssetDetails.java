package com.irrigaiq.assets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Editable asset attributes.
 *
 * @param operatingWindowStart start of the daily window the asset may run (solar hours, or the
 * grid's load-shedding-free period); irrigation start times are suggested inside it
 * @param baselineEnergyKwhPerM3 commissioning reference for detecting pump efficiency loss
 * @param operatingHours hour-meter value at registration (later accumulated automatically)
 */
public record AssetDetails(AssetType type, String name, UUID fieldId, UUID parentId, String manufacturer, String model,
		String serialNumber, LocalDate installedOn, Criticality criticality, AssetStatus status, PowerSource powerSource,
		BigDecimal ratedPowerKw, BigDecimal ratedFlowM3h, BigDecimal ratedHeadM, BigDecimal capacityM3,
		BigDecimal annualAllocationM3, LocalTime operatingWindowStart, LocalTime operatingWindowEnd,
		Integer serviceIntervalDays, Integer serviceIntervalHours, Integer expectedLifeYears, LocalDate lastServiceOn,
		BigDecimal baselineEnergyKwhPerM3, BigDecimal operatingHours, String notes) {
}
