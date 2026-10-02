package com.irrigaiq.ml;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Wire contract of the ML service (camelCase JSON). Field meanings are documented in IrrigaIQ-ML. */
public final class MlApi {

	private MlApi() {
	}

	// ---- Irrigation need ----

	/**
	 * @param soilMoisturePct on the model's scale (see {@link com.irrigaiq.agronomy.SoilMoistureScale})
	 * @param rainfallMmAnnualised the last 90 days of rain scaled to a year (the model was trained on annual totals)
	 */
	public record FieldFeatures(String fieldId, double soilMoisturePct, double temperatureC, double windSpeedKmh,
			double rainfallMmAnnualised, String growthPhase, boolean mulched) {
	}

	record IrrigationRequest(List<FieldFeatures> fields) {
	}

	public record Driver(String feature, String label, Object value, Object typical, double impact, String direction) {
	}

	/**
	 * @param need Low, Medium or High
	 * @param confidence probability of the predicted class
	 * @param outOfRange inputs outside the range the model was trained on
	 */
	public record IrrigationPrediction(String fieldId, String need, boolean irrigationRequired,
			Map<String, Double> probabilities, double probabilityRequired, double confidence, double needIndex,
			List<Driver> drivers, List<String> outOfRange, String modelVersion) {
	}

	record IrrigationResponse(List<IrrigationPrediction> predictions) {
	}

	// ---- Water anomaly ----

	/** @param litres volume in the hour starting at {@code at} (or the equivalent hourly rate) */
	public record FlowPoint(OffsetDateTime at, double litres, boolean scheduled) {
	}

	record WaterAnomalyRequest(String meterId, List<FlowPoint> history, List<FlowPoint> recent) {
	}

	public record FlowCheck(OffsetDateTime at, double observedLitres, Double expectedLitres, Double zScore,
			Double isolation, Double score, boolean anomalous) {
	}

	/** @param headline hedged wording, e.g. "Possible leakage or irrigation-system issue" */
	public record Anomaly(OffsetDateTime from, OffsetDateTime to, int hours, String direction,
			double observedLitresPerHour, double expectedLitresPerHour, double score, String severity,
			boolean duringScheduledIrrigation, String headline, List<String> possibleCauses) {
	}

	/** @param status {@code OK} or {@code INSUFFICIENT_HISTORY} */
	public record WaterAnomalyResult(String meterId, String status, String modelVersion, List<FlowCheck> points,
			Anomaly anomaly, String message) {
	}

	// ---- Equipment failure risk ----

	/** Ratios are against the pump's healthy baseline; any may be missing. */
	public record PumpReadings(Double energyRatio, Double energyTrend, Double flowRatio, Double pressureRatio,
			Double vibrationMmS, Double motorTempC, Double runtimeHPerDay, Double startsPerDay,
			Double serviceOverdueRatio, Double ageRatio) {
	}

	public record AssetFeatures(String assetId, PumpReadings readings) {
	}

	record EquipmentRequest(List<AssetFeatures> assets) {
	}

	public record Indicator(String feature, String label, String value, String healthy, double impact, String trend) {
	}

	public record EquipmentRisk(String assetId, double failureProbability, int horizonDays, String riskLevel,
			int healthScore, String recommendation, List<Indicator> indicators, List<String> missing,
			String modelVersion) {
	}

	record EquipmentResponse(List<EquipmentRisk> assets) {
	}

	// ---- Embeddings ----

	record EmbeddingRequest(List<String> texts, boolean query) {
	}

	public record EmbeddingResponse(String model, int dimensions, List<List<Double>> vectors) {
	}

}
