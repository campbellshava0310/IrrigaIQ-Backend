package com.irrigaiq.assets;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.irrigaiq.assets.AssetHealthEngine.Factor;
import com.irrigaiq.assets.AssetHealthEngine.Health;
import com.irrigaiq.assets.AssetHealthEngine.ServiceForecast;

/** Read models (and API bodies) of the infrastructure module. */
public final class AssetViews {

	private AssetViews() {
	}

	/**
	 * @param source {@code RULES} today; {@code MODEL} once sensor-trained models score the asset
	 */
	public record HealthView(int score, RiskLevel risk, List<Factor> factors, ServiceForecast service,
			Double energyKwhPerM3, Double energyDriftPct, String outlook, String recommendedAction, String source,
			String modelVersion) {

		static HealthView from(Health health) {
			return new HealthView(health.score(), health.risk(), health.factors(), health.service(),
					health.energyKwhPerM3(), health.energyDriftPct(), health.outlook(), health.recommendedAction(),
					"RULES", AssetHealthEngine.MODEL_VERSION);
		}

	}

	public record AssetView(UUID id, UUID farmId, UUID fieldId, UUID parentId, AssetType type,
			AssetType.Category category, String name, String manufacturer, String model, String serialNumber,
			LocalDate installedOn, Criticality criticality, AssetStatus status, PowerSource powerSource,
			BigDecimal ratedPowerKw, BigDecimal ratedFlowM3h, BigDecimal ratedHeadM, BigDecimal capacityM3,
			BigDecimal annualAllocationM3, LocalTime operatingWindowStart, LocalTime operatingWindowEnd,
			Integer serviceIntervalDays, Integer serviceIntervalHours, Integer expectedLifeYears,
			BigDecimal operatingHours, BigDecimal hoursAtLastService, LocalDate lastServiceOn,
			BigDecimal baselineEnergyKwhPerM3, String notes, HealthView health, Instant createdAt,
			Instant updatedAt) {

		public static AssetView from(Asset asset, Health health) {
			return new AssetView(asset.getId(), asset.getFarm().getId(), asset.getFieldId(), asset.getParentId(),
					asset.getType(), asset.getType().category(), asset.getName(), asset.getManufacturer(),
					asset.getModel(), asset.getSerialNumber(), asset.getInstalledOn(), asset.getCriticality(),
					asset.getStatus(), asset.getPowerSource(), asset.getRatedPowerKw(), asset.getRatedFlowM3h(),
					asset.getRatedHeadM(), asset.getCapacityM3(), asset.getAnnualAllocationM3(),
					asset.getOperatingWindowStart(), asset.getOperatingWindowEnd(), asset.getServiceIntervalDays(),
					asset.getServiceIntervalHours(), asset.getExpectedLifeYears(), asset.getOperatingHours(),
					asset.getHoursAtLastService(), asset.getLastServiceOn(), asset.getBaselineEnergyKwhPerM3(),
					asset.getNotes(), (health != null) ? HealthView.from(health) : null, asset.getCreatedAt(),
					asset.getUpdatedAt());
		}

	}

	public record ConditionCheckView(UUID id, UUID assetId, Instant checkedAt, OverallCondition overallCondition,
			BigDecimal pressureBar, BigDecimal flowM3h, BigDecimal powerKw, BigDecimal hourMeter,
			VibrationLevel vibration, boolean leakDetected, BigDecimal filterDpBar, BigDecimal waterLevelM,
			Double energyKwhPerM3, String notes) {

		public static ConditionCheckView from(AssetConditionCheck check) {
			return new ConditionCheckView(check.getId(), check.getAsset().getId(), check.getCheckedAt(),
					check.getOverallCondition(), check.getPressureBar(), check.getFlowM3h(), check.getPowerKw(),
					check.getHourMeter(), check.getVibration(), check.isLeakDetected(), check.getFilterDpBar(),
					check.getWaterLevelM(), check.energyKwhPerM3(), check.getNotes());
		}

	}

	public record AssetDetailView(AssetView asset, List<ConditionCheckView> recentChecks, List<AssetView> children,
			AssetView parent) {
	}

	/** Asset plus its current health, for other modules (alerts, dashboard, briefing). */
	public record AssetHealthSnapshot(Asset asset, Health health) {
	}

}
