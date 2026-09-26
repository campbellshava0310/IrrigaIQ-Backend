package com.irrigaiq.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.irrigaiq.agronomy.WaterBalanceEngine.Decision;
import com.irrigaiq.agronomy.WaterBalanceEngine.Recommendation;
import com.irrigaiq.agronomy.WaterBalanceEngine.WaterStatus;
import com.irrigaiq.assets.Asset;
import com.irrigaiq.assets.AssetHealthEngine.Factor;
import com.irrigaiq.assets.AssetHealthEngine.Health;
import com.irrigaiq.assets.AssetService;
import com.irrigaiq.assets.AssetStatus;
import com.irrigaiq.assets.AssetViews.AssetHealthSnapshot;
import com.irrigaiq.assets.Criticality;
import com.irrigaiq.assets.RiskLevel;
import com.irrigaiq.common.text.HumanText;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmRepository;
import com.irrigaiq.irrigation.IrrigationService.FieldWaterState;
import com.irrigaiq.irrigation.IrrigationService;
import com.irrigaiq.water.WaterService.SourceAllocation;
import com.irrigaiq.water.WaterService;
import com.irrigaiq.weather.WeatherDay;
import com.irrigaiq.weather.WeatherService;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the current state of fields, infrastructure, weather and water use into alerts. Idempotent:
 * each condition has a dedupe key, so re-running updates alerts in place, resolves the ones whose
 * condition has cleared, and never duplicates. Sensor anomaly detectors and ML models will add
 * candidates through the same path.
 */
@Component
public class AlertEvaluator {

	private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

	static final String SOURCE_RULES = "RULES";

	static final double HEAVY_RAIN_MM = 25;

	/** A person's "resolve" silences the same condition for this long. */
	static final Duration DISMISS_PERIOD = Duration.ofHours(24);

	private final FarmRepository farms;

	private final IrrigationService irrigation;

	private final AssetService assets;

	private final WaterService water;

	private final WeatherService weather;

	private final AlertRepository alerts;

	private final Clock clock;

	public AlertEvaluator(FarmRepository farms, IrrigationService irrigation, AssetService assets, WaterService water,
			WeatherService weather, AlertRepository alerts, Clock clock) {
		this.farms = farms;
		this.irrigation = irrigation;
		this.assets = assets;
		this.water = water;
		this.weather = weather;
		this.alerts = alerts;
		this.clock = clock;
	}

	record Candidate(AlertType type, AlertSeverity severity, String title, String message, UUID fieldId, UUID assetId,
			Map<String, Object> details) {
	}

	/** Result of one evaluation, for logging and the "re-check now" endpoint. */
	public record Outcome(int raised, int updated, int resolved) {
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Outcome evaluate(UUID farmId) {
		return farms.findById(farmId).map(this::evaluate).orElse(new Outcome(0, 0, 0));
	}

	@Transactional
	public Outcome evaluate(Farm farm) {
		Map<String, Candidate> candidates = new LinkedHashMap<>();
		fieldCandidates(farm, candidates);
		weatherCandidates(farm, candidates);
		assetCandidates(farm, candidates);
		allocationCandidates(farm, candidates);

		Instant now = clock.instant();
		Map<String, Alert> open = new HashMap<>();
		for (Alert alert : alerts.findByFarmIdAndResolvedAtIsNull(farm.getId())) {
			if (SOURCE_RULES.equals(alert.getSource()) && alert.getDedupeKey() != null) {
				open.put(alert.getDedupeKey(), alert);
			}
		}

		int raised = 0;
		int updated = 0;
		for (Map.Entry<String, Candidate> entry : candidates.entrySet()) {
			Candidate c = entry.getValue();
			Alert alert = open.remove(entry.getKey());
			if (alert == null) {
				if (alerts.recentlyDismissed(farm.getId(), entry.getKey(), now.minus(DISMISS_PERIOD))) {
					continue;
				}
				alert = new Alert(farm, entry.getKey(), SOURCE_RULES, now);
				raised++;
			}
			else {
				updated++;
			}
			alert.apply(c.type(), c.severity(), c.title(), c.message(), c.fieldId(), c.assetId(), c.details(), now);
			alerts.save(alert);
		}
		for (Alert cleared : open.values()) {
			cleared.resolve(null, now);
		}
		if (raised > 0 || !open.isEmpty()) {
			log.debug("Alerts for farm {}: {} raised, {} updated, {} auto-resolved", farm.getId(), raised, updated,
					open.size());
		}
		return new Outcome(raised, updated, open.size());
	}

	private void fieldCandidates(Farm farm, Map<String, Candidate> out) {
		for (FieldWaterState state : irrigation.evaluate(farm)) {
			Recommendation rec = state.recommendation();
			if (rec.decision() == Decision.SCHEDULED || rec.decision() == Decision.HOLD_FOR_RAIN) {
				continue;
			}
			String field = state.field().getName();
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("depletionPct", round(state.result().depletionPct()));
			details.put("grossDepthMm", round(rec.grossDepthMm()));
			details.put("volumeM3", round(rec.volumeM3()));
			details.put("durationMinutes", rec.durationMinutes());
			details.put("reasons", rec.reasons());
			String key = "field-water:" + state.field().getId();
			if (rec.status() == WaterStatus.STRESSED) {
				out.put(key, new Candidate(AlertType.CROP_WATER_STRESS, AlertSeverity.CRITICAL,
						field + " is under water stress",
						String.format(Locale.ENGLISH,
								"The root zone is %.0f%% depleted and yield is being lost. Irrigate about %.0f mm (%.0f m³) now.",
								state.result().depletionPct(), rec.grossDepthMm(), rec.volumeM3()),
						state.field().getId(), null, details));
			}
			else if (rec.decision() == Decision.IRRIGATE_NOW) {
				out.put(key, new Candidate(AlertType.IRRIGATION_REQUIRED, AlertSeverity.WARNING,
						"Irrigate " + field + " today",
						String.format(Locale.ENGLISH,
								"Soil water has reached the stress threshold. Apply about %.0f mm (%.0f m³, about %s).",
								rec.grossDepthMm(), rec.volumeM3(), HumanText.duration(rec.durationMinutes())),
						state.field().getId(), null, details));
			}
		}
	}

	private void weatherCandidates(Farm farm, Map<String, Candidate> out) {
		LocalDate today = weather.today(farm);
		List<WeatherDay> next = weather.series(farm, today, today.plusDays(1)).days();
		double rain = next.stream()
			.mapToDouble((day) -> (day.precipitationMm() != null) ? day.precipitationMm()
					* ((day.precipitationProbability() != null) ? day.precipitationProbability() / 100.0 : 1) : 0)
			.sum();
		if (rain >= HEAVY_RAIN_MM) {
			out.put("weather:heavy-rain", new Candidate(AlertType.RAIN_EXPECTED, AlertSeverity.INFO,
					"Heavy rain expected",
					String.format(Locale.ENGLISH,
							"About %.0f mm of rain is forecast in the next 48 h. Hold planned irrigation and check drainage.",
							rain),
					null, null, Map.of("expectedRainMm", round(rain))));
		}
	}

	private void assetCandidates(Farm farm, Map<String, Candidate> out) {
		for (AssetHealthSnapshot snapshot : assets.assess(farm)) {
			Asset asset = snapshot.asset();
			Health health = snapshot.health();
			if (asset.getStatus() == AssetStatus.RETIRED) {
				continue;
			}
			String key = "asset:" + asset.getId();
			Factor top = health.factors().stream().filter((f) -> f.impact() < 0).findFirst().orElse(null);
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("healthScore", health.score());
			details.put("risk", health.risk().name());
			details.put("recommendedAction", health.recommendedAction());
			if (top != null) {
				details.put("factor", top.label());
			}
			boolean critical = asset.getCriticality() == Criticality.HIGH
					|| asset.getCriticality() == Criticality.CRITICAL;

			if (asset.getStatus() == AssetStatus.DOWN) {
				out.put(key, new Candidate(AlertType.ASSET_DOWN, AlertSeverity.CRITICAL, asset.getName() + " is down",
						"Out of service. " + health.recommendedAction(), asset.getFieldId(), asset.getId(), details));
			}
			else if (health.risk() == RiskLevel.HIGH || health.risk() == RiskLevel.CRITICAL) {
				AlertType type = typeFor(top);
				AlertSeverity severity = (health.risk() == RiskLevel.CRITICAL || critical) ? AlertSeverity.CRITICAL
						: AlertSeverity.WARNING;
				String title = switch (type) {
					case PUMP_EFFICIENCY_DROP -> asset.getName() + ": energy use per m³ rising";
					case FILTER_CLOGGING -> asset.getName() + " needs cleaning";
					case LEAK_REPORTED -> "Leak at " + asset.getName();
					case SERVICE_OVERDUE -> asset.getName() + " service overdue";
					case WATER_LEVEL_FALLING -> asset.getName() + ": water table falling";
					default -> asset.getName() + " at risk of failure";
				};
				out.put(key, new Candidate(type, severity, title,
						((top != null) ? top.detail() + " " : "") + health.recommendedAction(), asset.getFieldId(),
						asset.getId(), details));
			}
			else if (top != null && "SERVICE_OVERDUE".equals(top.code())) {
				out.put(key, new Candidate(AlertType.SERVICE_OVERDUE,
						critical ? AlertSeverity.WARNING : AlertSeverity.INFO, asset.getName() + " service overdue",
						top.detail() + " " + top.action(), asset.getFieldId(), asset.getId(), details));
			}
		}
	}

	private void allocationCandidates(Farm farm, Map<String, Candidate> out) {
		LocalDate today = weather.today(farm);
		List<SourceAllocation> allocations = water.summarize(farm, today, today).allocations();
		for (SourceAllocation allocation : allocations) {
			if (allocation.usedPct() < 90) {
				continue;
			}
			boolean exceeded = allocation.usedPct() >= 100;
			out.put("allocation:" + allocation.assetId(), new Candidate(AlertType.WATER_ALLOCATION,
					exceeded ? AlertSeverity.CRITICAL : AlertSeverity.WARNING,
					allocation.name() + (exceeded ? " allocation exceeded" : " allocation almost used"),
					String.format(Locale.ENGLISH,
							"%.0f of %.0f m³ drawn this year (%.0f%%). Prioritise high-value fields and check for leaks.",
							allocation.usedYearToDateM3(), allocation.annualAllocationM3(), allocation.usedPct()),
					null, allocation.assetId(), Map.of("usedPct", allocation.usedPct())));
		}
	}

	private static AlertType typeFor(Factor factor) {
		if (factor == null) {
			return AlertType.ASSET_FAILURE_RISK;
		}
		return switch (factor.code()) {
			case "ENERGY_DRIFT" -> AlertType.PUMP_EFFICIENCY_DROP;
			case "FILTER_CLOGGING" -> AlertType.FILTER_CLOGGING;
			case "LEAK" -> AlertType.LEAK_REPORTED;
			case "SERVICE_OVERDUE" -> AlertType.SERVICE_OVERDUE;
			case "WATER_LEVEL_FALLING" -> AlertType.WATER_LEVEL_FALLING;
			default -> AlertType.ASSET_FAILURE_RISK;
		};
	}

	private static double round(double value) {
		return Math.round(value * 10) / 10.0;
	}

}
