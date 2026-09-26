package com.irrigaiq.irrigation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.irrigaiq.agronomy.GrowthPhase;
import com.irrigaiq.agronomy.WaterBalanceEngine.Decision;
import com.irrigaiq.agronomy.WaterBalanceEngine.WaterStatus;
import com.irrigaiq.assets.PowerSource;
import com.irrigaiq.farm.CropType;
import com.irrigaiq.farm.IrrigationMethod;
import com.irrigaiq.farm.SoilType;

/** Read models (and API bodies) of the irrigation module. Depths in mm, volumes in m³. */
public final class IrrigationViews {

	private IrrigationViews() {
	}

	public enum Confidence {

		HIGH, MEDIUM, LOW

	}

	public record PumpRef(UUID id, String name, PowerSource powerSource) {
	}

	/**
	 * @param windowLabel why this start time: solar hours, the pump's power window, or cool hours
	 * @param sessions number of runs when the job does not fit the pump's daily window
	 */
	public record ScheduleView(LocalTime startTime, int sessions, int sessionMinutes, String windowLabel, String note) {
	}

	/**
	 * @param source the recommending method: the FAO-56 water balance today, an ML model later
	 */
	public record RecommendationView(Decision decision, WaterStatus status, LocalDate irrigateOn,
			Integer daysUntilStress, double netDepthMm, double grossDepthMm, double volumeM3, int durationMinutes,
			ScheduleView schedule, Double energyKwh, Double energyCost, String currency, double expectedRainMm,
			List<String> reasons, Confidence confidence, String confidenceNote, String source, String modelVersion) {
	}

	public record EventView(UUID id, UUID fieldId, String fieldName, IrrigationStatus status, IrrigationSource source,
			Instant startedAt, int durationMinutes, BigDecimal grossDepthMm, double volumeM3, UUID pumpAssetId,
			BigDecimal energyKwh, String notes, Instant completedAt, boolean overdue) {
	}

	public record FieldStatusView(UUID fieldId, String fieldName, BigDecimal areaHa, CropType cropType, String variety,
			LocalDate plantingDate, GrowthPhase phase, Integer daysAfterPlanting, Integer seasonLengthDays,
			IrrigationMethod irrigationMethod, boolean systemConfigured, PumpRef pump, double depletionMm,
			double depletionPct, double stressThresholdPct, double tawMm, RecommendationView recommendation,
			EventView nextPlanned, EventView lastIrrigation, Double yieldAtRiskPct, Integer stressDays) {
	}

	public record DayView(LocalDate date, boolean forecast, double kc, double et0Mm, double etcMm, double etaMm,
			double rainMm, double effectiveRainMm, double irrigationMm, double plannedIrrigationMm, double depletionMm,
			double depletionPct, double stressThresholdPct, double tawMm, double ks) {
	}

	public record SeasonView(int daysAfterPlanting, int seasonLengthDays, GrowthPhase phase, double kc, double etcMm,
			double etaMm, double effectiveRainMm, double irrigationMm, int stressDays, double relativeEtPct,
			double yieldReductionPct, double attainableYieldTHa, double expectedYieldTHa) {
	}

	/** The irrigation system the model assumed, flagging values that fell back to defaults. */
	public record SetupView(IrrigationMethod method, boolean methodDefaulted, double applicationRateMmH,
			boolean rateDefaulted, double efficiency, boolean efficiencyDefaulted, SoilType soilType,
			boolean soilDefaulted, double availableWaterMmPerM, PumpRef pump) {
	}

	public record WaterBalanceView(LocalDate today, FieldStatusView status, SeasonView season, SetupView setup,
			List<DayView> days, boolean weatherLive, boolean locationApproximate) {
	}

	public record ObservationView(UUID id, UUID fieldId, Instant observedAt, BigDecimal depletionPct,
			ObservationMethod method, String notes) {

		static ObservationView from(SoilObservation observation) {
			return new ObservationView(observation.getId(), observation.getField().getId(),
					observation.getObservedAt(), observation.getDepletionPct(), observation.getMethod(),
					observation.getNotes());
		}

	}

}
