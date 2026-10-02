package com.irrigaiq.assistant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.irrigaiq.alerts.AlertService;
import com.irrigaiq.alerts.AlertService.AlertView;
import com.irrigaiq.assets.AssetHealthEngine.Factor;
import com.irrigaiq.assets.AssetService;
import com.irrigaiq.assets.AssetViews.AssetView;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.intelligence.BriefingService;
import com.irrigaiq.intelligence.DashboardService;
import com.irrigaiq.intelligence.DashboardService.Dashboard;
import com.irrigaiq.irrigation.IrrigationService;
import com.irrigaiq.irrigation.IrrigationViews.EventView;
import com.irrigaiq.irrigation.IrrigationViews.FieldStatusView;
import com.irrigaiq.irrigation.IrrigationViews.ObservationView;
import com.irrigaiq.irrigation.IrrigationViews.RecommendationView;
import com.irrigaiq.knowledge.KnowledgeDocument.Source;
import com.irrigaiq.knowledge.KnowledgeService;
import com.irrigaiq.knowledge.KnowledgeService.Passage;
import com.irrigaiq.knowledge.KnowledgeService.SearchResult;
import com.irrigaiq.ml.EquipmentRiskService;
import com.irrigaiq.ml.EquipmentRiskService.EquipmentRiskView;
import com.irrigaiq.ml.EquipmentRiskService.PumpRisk;
import com.irrigaiq.ml.IrrigationModelService;
import com.irrigaiq.ml.IrrigationModelService.FieldAssessment;
import com.irrigaiq.ml.IrrigationModelService.IrrigationModelView;
import com.irrigaiq.ml.MlApi.Driver;
import com.irrigaiq.ml.MlApi.Indicator;
import com.irrigaiq.ml.WaterAnomalyService;
import com.irrigaiq.ml.WaterAnomalyService.HourPoint;
import com.irrigaiq.ml.WaterAnomalyService.MeterCheck;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.telemetry.DeviceService;
import com.irrigaiq.telemetry.SensorType;
import com.irrigaiq.telemetry.TelemetryViews.DeviceView;
import com.irrigaiq.water.WaterService;
import com.irrigaiq.water.WaterService.PeriodTotals;
import com.irrigaiq.water.WaterService.WaterSummary;
import com.irrigaiq.weather.WeatherDay;
import com.irrigaiq.weather.WeatherService;
import com.irrigaiq.weather.WeatherService.WeatherSeries;

import org.springframework.stereotype.Component;

/**
 * The assistant's tools: read-only views of one farm, run with the asking user's permissions. Each
 * returns compact JSON with units in the key names, times in the farm's local time, and a
 * {@code note} whenever data is missing, so the model can say so instead of guessing.
 */
@Component
class FarmTools {

	/** What a tool call needs: the farm, the user (permissions) and where knowledge sources are collected. */
	record Context(Farm farm, AuthenticatedUser user, Map<String, Source> sources) {
	}

	/** An expected problem with the call (unknown field, ambiguous name): returned to the model as an error. */
	static final class ToolException extends RuntimeException {

		ToolException(String message) {
			super(message);
		}

	}

	private static final Set<String> GENERIC_WORDS = Set.of("field", "block", "the", "paddock", "plot", "pump",
			"meter", "no", "number");

	private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ENGLISH);

	private final DashboardService dashboard;

	private final BriefingService briefing;

	private final IrrigationService irrigation;

	private final IrrigationModelService irrigationModel;

	private final WaterService water;

	private final WeatherService weather;

	private final WaterAnomalyService anomalies;

	private final AssetService assets;

	private final EquipmentRiskService equipmentRisk;

	private final AlertService alerts;

	private final KnowledgeService knowledge;

	private final DeviceService devices;

	private final List<ChatModel.ToolSpec> specs;

	FarmTools(DashboardService dashboard, BriefingService briefing, IrrigationService irrigation,
			IrrigationModelService irrigationModel, WaterService water, WeatherService weather,
			WaterAnomalyService anomalies, AssetService assets, EquipmentRiskService equipmentRisk,
			AlertService alerts, KnowledgeService knowledge, DeviceService devices) {
		this.dashboard = dashboard;
		this.briefing = briefing;
		this.irrigation = irrigation;
		this.irrigationModel = irrigationModel;
		this.water = water;
		this.weather = weather;
		this.anomalies = anomalies;
		this.assets = assets;
		this.equipmentRisk = equipmentRisk;
		this.alerts = alerts;
		this.knowledge = knowledge;
		this.devices = devices;
		this.specs = List.of(
				spec("getFarmSummary",
						"Overview of the farm today: fields by water status, this week's water use against crop need, "
								+ "equipment at risk, open alerts and the prioritised task list. Start here for broad questions.",
						Map.of(), List.of()),
				spec("getFieldStatus",
						"Everything about one field: crop and growth stage, root-zone depletion against the stress threshold, "
								+ "the irrigation recommendation, the ML model's view, the probe reading and the last and next irrigation.",
						Map.of("field", fieldParam(true)), List.of("field")),
				spec("getSoilMoisture",
						"Soil moisture per field: root-zone depletion (0 % = field capacity), the crop's stress threshold, "
								+ "the latest probe reading if a probe is installed, and the latest manual soil check.",
						Map.of("field", fieldParam(false)), List.of()),
				spec("getWaterUsage",
						"Water applied over the last N days (default 7) against crop requirement, per field, with energy, "
								+ "cost, savings against a conventional schedule and licensed allocations.",
						Map.of("days", intParam("Days up to and including today", 1, 365)), List.of()),
				spec("compareWaterUsage",
						"Compares water use, crop requirement, savings and energy for the last N days (default 7) with the "
								+ "N days before.",
						Map.of("days", intParam("Length of each period in days", 3, 180)), List.of()),
				spec("getIrrigationRecommendation",
						"The irrigation recommendation for one or all fields: decision, date, depth, volume, run time, "
								+ "start time and reasons from the FAO-56 water balance, plus the ML model's need rating, "
								+ "confidence, drivers and whether it agrees.",
						Map.of("field", fieldParam(false)), List.of()),
				spec("getIrrigationHistory",
						"Irrigations logged or planned in the last N days (default 14), optionally for one field.",
						Map.of("field", fieldParam(false), "days", intParam("How many days back", 1, 90)), List.of()),
				spec("getWeatherForecast",
						"Daily forecast for the farm: reference evapotranspiration (ET0), rain and its probability, "
								+ "temperatures and wind, for the next N days (default 7).",
						Map.of("days", intParam("Days ahead including today", 1, 7)), List.of()),
				spec("getWaterAnomalies",
						"Anomaly check of every water meter: whether recent flow is abnormal against the meter's own "
								+ "history, observed against expected litres per hour, severity and possible causes.",
						Map.of(), List.of()),
				spec("getEquipmentHealth",
						"Rule-based health of pumps, filters, lines and other assets: score, risk, the factors behind "
								+ "deductions with their fixes, and service due dates. Optionally one asset.",
						Map.of("asset", assetParam()), List.of()),
				spec("getEquipmentRisk",
						"The ML model's 14-day failure probability for each pump, with the readings driving it, the "
								+ "recommended action and which inputs were missing. Optionally one pump.",
						Map.of("asset", assetParam()), List.of()),
				spec("getActiveAlerts",
						"Open and acknowledged alerts: type, severity, title, message, field or asset, and when raised.",
						Map.of(), List.of()),
				spec("searchAgricultureKnowledge",
						"Searches IrrigaIQ's curated agronomy and maintenance notes (FAO-56 irrigation scheduling, crop "
								+ "water needs, soil-moisture probes, drip and pump maintenance, leaks, salinity, mulching, "
								+ "solar pumping, how IrrigaIQ's models work). Returns passages with their published sources.",
						Map.of("query", Map.of("type", "string", "minLength", 3, "maxLength", 300, "description",
								"What to look up, in plain words")),
						List.of("query")),
				spec("generateFarmReport",
						"Collects the data for a farm report over the last N days (default 7): summary, water use against "
								+ "the previous period, recommendations, alerts, water anomalies and equipment risk. Write the "
								+ "report from the result.",
						Map.of("days", intParam("Days covered by the report", 7, 90)), List.of()));
	}

	List<ChatModel.ToolSpec> specs() {
		return specs;
	}

	/** Short, present-tense label for the UI while a tool runs. */
	static String label(String tool) {
		return switch (tool) {
			case "getFarmSummary" -> "Reviewing the farm";
			case "getFieldStatus" -> "Checking the field";
			case "getSoilMoisture" -> "Reading soil moisture";
			case "getWaterUsage" -> "Totalling water use";
			case "compareWaterUsage" -> "Comparing water use";
			case "getIrrigationRecommendation" -> "Checking irrigation recommendations";
			case "getIrrigationHistory" -> "Reading the irrigation log";
			case "getWeatherForecast" -> "Checking the forecast";
			case "getWaterAnomalies" -> "Checking water meters";
			case "getEquipmentHealth" -> "Checking equipment health";
			case "getEquipmentRisk" -> "Scoring pump failure risk";
			case "getActiveAlerts" -> "Reading alerts";
			case "searchAgricultureKnowledge" -> "Searching the knowledge base";
			case "generateFarmReport" -> "Gathering report data";
			default -> "Working";
		};
	}

	Object run(String tool, Map<String, Object> input, Context context) {
		return switch (tool) {
			case "getFarmSummary" -> farmSummary(context);
			case "getFieldStatus" -> fieldStatus(context, text(input, "field"));
			case "getSoilMoisture" -> soilMoisture(context, text(input, "field"));
			case "getWaterUsage" -> waterUsage(context, integer(input, "days", 7));
			case "compareWaterUsage" -> compareWater(context, integer(input, "days", 7));
			case "getIrrigationRecommendation" -> recommendations(context, text(input, "field"));
			case "getIrrigationHistory" -> history(context, text(input, "field"), integer(input, "days", 14));
			case "getWeatherForecast" -> forecast(context, integer(input, "days", 7));
			case "getWaterAnomalies" -> waterAnomalies(context);
			case "getEquipmentHealth" -> equipmentHealth(context, text(input, "asset"));
			case "getEquipmentRisk" -> equipmentRisk(context, text(input, "asset"));
			case "getActiveAlerts" -> activeAlerts(context);
			case "searchAgricultureKnowledge" -> searchKnowledge(context, text(input, "query"));
			case "generateFarmReport" -> report(context, integer(input, "days", 7));
			default -> throw new ToolException("Unknown tool " + tool);
		};
	}

	// ---- Tools ----

	private Map<String, Object> farmSummary(Context c) {
		Dashboard d = dashboard.dashboard(c.farm().getId(), c.user());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("farm", d.farmName());
		out.put("location", d.location());
		out.put("today", d.today());
		out.put("weatherLive", d.weatherLive());
		out.put("headline", briefing.briefing(c.farm().getId(), c.user()).headline());
		out.put("fields", Map.of("total", d.fields().total(), "planted", d.fields().planted(), "byWaterStatus",
				d.fields().byStatus(), "items", d.fields().items().stream().map((f) -> compact(Map.of(
						"name", f.name(), "crop", String.valueOf(f.cropType()), "phase", String.valueOf(f.phase()),
						"waterStatus", String.valueOf(f.status()), "depletionPct", f.depletionPct(),
						"stressThresholdPct", f.stressThresholdPct(), "irrigateOn", String.valueOf(f.irrigateOn()))))
					.toList()));
		Map<String, Object> waterKpis = new LinkedHashMap<>();
		waterKpis.put("weekAppliedM3", d.water().weekAppliedM3());
		waterKpis.put("previousWeekAppliedM3", d.water().previousWeekAppliedM3());
		waterKpis.put("weekRequirementM3", d.water().weekRequirementM3());
		waterKpis.put("weekAdequacyPct", d.water().weekAdequacyPct());
		waterKpis.put("monthSavedM3", d.water().monthSavedM3());
		waterKpis.put("weekEnergyKwh", d.water().weekEnergyKwh());
		waterKpis.put("weekEnergyCost", d.water().weekEnergyCost());
		waterKpis.put("currency", d.water().currency());
		out.put("water", waterKpis);
		out.put("equipment", Map.of("total", d.assets().total(), "byRisk", d.assets().byRisk(), "down", d.assets().down(),
				"serviceOverdue", d.assets().serviceOverdue(), "needingAttention", d.assets().attention().stream()
					.map((a) -> compact(Map.of("name", a.name(), "healthScore", a.healthScore(), "risk",
							String.valueOf(a.risk()), "topFactor", String.valueOf(a.topFactor()))))
					.toList()));
		out.put("maintenance", d.maintenance());
		out.put("alerts", d.alerts());
		out.put("tasks", d.tasks().stream().limit(6).map((t) -> compact(Map.of("title", t.title(), "detail",
				String.valueOf(t.detail()), "dueOn", String.valueOf(t.dueOn())))).toList());
		return out;
	}

	private Map<String, Object> fieldStatus(Context c, String query) {
		FieldStatusView field = matchField(c, query);
		Map<String, Object> out = fieldCore(field);
		out.put("recommendation", recommendation(field.recommendation()));
		out.put("model", modelFor(c, field.fieldId()));
		out.put("probe", probeFor(c, field.fieldId()));
		out.put("lastIrrigation", (field.lastIrrigation() != null) ? event(c, field.lastIrrigation()) : null);
		out.put("nextPlanned", (field.nextPlanned() != null) ? event(c, field.nextPlanned()) : null);
		out.put("yieldAtRiskPct", field.yieldAtRiskPct());
		out.put("stressDaysThisSeason", field.stressDays());
		return out;
	}

	private Map<String, Object> soilMoisture(Context c, String query) {
		List<FieldStatusView> fields = (query != null) ? List.of(matchField(c, query)) : status(c);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (FieldStatusView field : fields) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("field", field.fieldName());
			row.put("depletionPct", field.depletionPct());
			row.put("stressThresholdPct", field.stressThresholdPct());
			row.put("availableWaterMm", field.tawMm());
			row.put("waterStatus", String.valueOf(field.recommendation().status()));
			row.put("probe", probeFor(c, field.fieldId()));
			List<ObservationView> checks = irrigation.observations(c.farm().getId(), field.fieldId(), c.user());
			row.put("latestSoilCheck", checks.isEmpty() ? null : compact(Map.of("at", local(c, checks.get(0).observedAt()),
					"depletionPct", checks.get(0).depletionPct(), "method", String.valueOf(checks.get(0).method()))));
			rows.add(row);
		}
		return Map.of("scale", "depletion: 0 % = field capacity, 100 % = wilting point; stress beyond the threshold",
				"fields", rows);
	}

	private Map<String, Object> waterUsage(Context c, int days) {
		LocalDate today = weather.today(c.farm());
		WaterSummary s = water.summary(c.farm().getId(), c.user(), today.minusDays(days - 1L), today);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("from", s.from());
		out.put("to", s.to());
		out.put("appliedM3", r1(s.appliedM3()));
		out.put("plannedM3", r1(s.plannedM3()));
		out.put("cropRequirementM3", r1(s.requirementM3()));
		out.put("adequacyPct", s.adequacyPct());
		out.put("savedVsConventionalM3", r1(s.savedM3()));
		out.put("savedPct", s.savedPct());
		out.put("conventionalSchedule", s.baselineDescription());
		out.put("energyKwh", r1(s.energyKwh()));
		out.put("energyCost", s.energyCost());
		out.put("currency", s.currency());
		out.put("byField", s.fields().stream().map((f) -> Map.of("field", f.fieldName(), "appliedM3", r1(f.appliedM3()),
				"cropRequirementM3", r1(f.requirementM3()), "irrigations", f.irrigations())).toList());
		out.put("allocations", s.allocations().stream().map((a) -> Map.of("source", a.name(), "annualAllocationM3",
				r1(a.annualAllocationM3()), "usedYearToDateM3", r1(a.usedYearToDateM3()), "usedPct", r1(a.usedPct())))
			.toList());
		return out;
	}

	private Map<String, Object> compareWater(Context c, int days) {
		LocalDate today = weather.today(c.farm());
		WaterSummary s = water.summary(c.farm().getId(), c.user(), today.minusDays(days - 1L), today);
		PeriodTotals p = s.previous();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("current", Map.of("from", s.from(), "to", s.to(), "appliedM3", r1(s.appliedM3()), "cropRequirementM3",
				r1(s.requirementM3()), "savedM3", r1(s.savedM3()), "energyKwh", r1(s.energyKwh())));
		if (p == null) {
			out.put("note", "The previous period is outside the recorded history, so no like-for-like comparison.");
			return out;
		}
		out.put("previous", Map.of("from", p.from(), "to", p.to(), "appliedM3", r1(p.appliedM3()), "cropRequirementM3",
				r1(p.requirementM3()), "savedM3", r1(p.savedM3()), "energyKwh", r1(p.energyKwh())));
		out.put("change", Map.of("appliedPct", pct(s.appliedM3(), p.appliedM3()), "cropRequirementPct",
				pct(s.requirementM3(), p.requirementM3()), "energyPct", pct(s.energyKwh(), p.energyKwh())));
		return out;
	}

	private Map<String, Object> recommendations(Context c, String query) {
		List<FieldStatusView> fields = (query != null) ? List.of(matchField(c, query)) : status(c);
		IrrigationModelView model = irrigationModel.assess(c.farm().getId(), c.user());
		Map<UUID, FieldAssessment> byField = model.fields().stream()
			.collect(Collectors.toMap(FieldAssessment::fieldId, Function.identity()));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (FieldStatusView field : fields) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("field", field.fieldName());
			row.put("crop", String.valueOf(field.cropType()));
			row.put("depletionPct", field.depletionPct());
			row.put("stressThresholdPct", field.stressThresholdPct());
			row.put("waterBalance", recommendation(field.recommendation()));
			row.put("model", assessment(byField.get(field.fieldId())));
			rows.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("modelAvailable", model.available());
		out.put("fields", rows);
		return out;
	}

	private Map<String, Object> history(Context c, String query, int days) {
		UUID fieldId = (query != null) ? matchField(c, query).fieldId() : null;
		LocalDate today = weather.today(c.farm());
		List<EventView> events = irrigation.events(c.farm().getId(), c.user(), today.minusDays(days), today.plusDays(7),
				fieldId, null);
		return Map.of("from", today.minusDays(days), "to", today.plusDays(7), "irrigations",
				events.stream().limit(60).map((e) -> event(c, e)).toList(), "count", events.size());
	}

	private Map<String, Object> forecast(Context c, int days) {
		LocalDate today = weather.today(c.farm());
		WeatherSeries series = weather.series(c.farm(), today, today.plusDays(days - 1L));
		return Map.of("live", series.live(), "source", series.live() ? "Open-Meteo forecast" : "Climatology estimate (offline)",
				"days", series.days().stream().map(FarmTools::weatherDay).toList());
	}

	/**
	 * Each meter with a one-line summary to repeat. Hourly flow is only included while the meter is
	 * reporting: old readings from a silent meter read like current ones, and small models do misread them.
	 */
	private Map<String, Object> waterAnomalies(Context c) {
		List<MeterCheck> meters = anomalies.check(c.farm().getId(), c.user()).meters();
		if (meters.isEmpty()) {
			return Map.of("note", "No water meters are registered on this farm, so flow cannot be checked.");
		}
		return Map.of("meters", meters.stream().map((m) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("meter", m.meterName());
			row.put("on", (m.assetName() != null) ? m.assetName() : m.fieldName());
			row.put("status", m.status());
			row.put("summary", meterSummary(c, m));
			row.put("lastReading", local(c, m.lastReadingAt()));
			if (m.anomaly() != null) {
				row.put("anomaly", compact(Map.of("headline", m.anomaly().headline(), "severity", m.anomaly().severity(),
						"direction", m.anomaly().direction(), "since", local(c, m.anomaly().from().toInstant()), "hours",
						m.anomaly().hours(), "observedLitresPerHour", r1(m.anomaly().observedLitresPerHour()),
						"expectedLitresPerHour", r1(m.anomaly().expectedLitresPerHour()), "anomalyScore",
						m.anomaly().score(), "duringScheduledIrrigation", m.anomaly().duringScheduledIrrigation(),
						"possibleCauses", m.anomaly().possibleCauses())));
			}
			if ("OK".equals(m.status()) || "ANOMALY".equals(m.status())) {
				List<HourPoint> hours = m.hours();
				row.put("lastHours", hours.subList(Math.max(0, hours.size() - 6), hours.size())
					.stream()
					.map((h) -> compact(Map.of("hourStarting", local(c, h.hour()), "observedLitresPerHour",
							h.observedLitres(), "expectedLitresPerHour", String.valueOf(h.expectedLitres()),
							"irrigationScheduled", h.scheduled())))
					.toList());
			}
			return row;
		}).toList());
	}

	private static String meterSummary(Context c, MeterCheck m) {
		return switch (m.status()) {
			case "ANOMALY" -> String.format(Locale.ENGLISH,
					"Possible anomaly: about %.0f L/h since %s against %.0f L/h expected%s.",
					m.anomaly().observedLitresPerHour(), local(c, m.anomaly().from().toInstant()),
					m.anomaly().expectedLitresPerHour(),
					m.anomaly().duringScheduledIrrigation() ? " during scheduled irrigation"
							: ", with no irrigation scheduled");
			case "OK" -> "Normal: recent flow matches this meter's usual pattern.";
			case "NOT_REPORTING" -> "Not reporting since " + local(c, m.lastReadingAt())
					+ ", so current water use cannot be checked. Check the meter's power and connection.";
			case "INSUFFICIENT_HISTORY" -> "Still learning this meter's normal pattern (it needs about three days of "
					+ "readings), so it cannot be judged yet.";
			default -> "The anomaly check is unavailable right now (model offline); flow is still being recorded.";
		};
	}

	private Map<String, Object> equipmentHealth(Context c, String query) {
		List<AssetView> list = assets.list(c.farm().getId(), c.user());
		if (query != null) {
			list = List.of(match(query, list, AssetView::name, AssetView::id, "asset"));
		}
		return Map.of("assets", list.stream().limit(20).map((a) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", a.name());
			row.put("type", String.valueOf(a.type()));
			row.put("status", String.valueOf(a.status()));
			if (a.health() != null) {
				row.put("healthScore", a.health().score());
				row.put("risk", String.valueOf(a.health().risk()));
				row.put("factors", a.health().factors().stream().filter((f) -> f.impact() < 0).limit(4)
					.map(FarmTools::factor).toList());
				row.put("recommendedAction", a.health().recommendedAction());
				row.put("energyKwhPerM3", a.health().energyKwhPerM3());
				row.put("energyDriftPct", a.health().energyDriftPct());
				if (a.health().service() != null) {
					row.put("serviceDueOn", String.valueOf(a.health().service().dueOn()));
					row.put("serviceOverdue", a.health().service().overdue());
				}
			}
			return row;
		}).toList());
	}

	private Map<String, Object> equipmentRisk(Context c, String query) {
		EquipmentRiskView view = equipmentRisk.assess(c.farm().getId(), c.user());
		List<PumpRisk> pumps = view.pumps();
		if (query != null) {
			pumps = List.of(match(query, pumps, PumpRisk::assetName, PumpRisk::assetId, "pump"));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("modelAvailable", view.available());
		if (!view.available()) {
			out.put("note", "The failure-risk model is offline; only the rule-based health scores are available.");
		}
		out.put("pumps", pumps.stream().map((p) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("pump", p.assetName());
			row.put("ruleHealthScore", p.ruleHealthScore());
			row.put("ruleRisk", String.valueOf(p.ruleRisk()));
			row.put("dataSources", p.sources());
			if (p.risk() != null) {
				row.put("failureProbability", p.risk().failureProbability());
				row.put("horizonDays", p.risk().horizonDays());
				row.put("riskLevel", p.risk().riskLevel());
				row.put("recommendation", p.risk().recommendation());
				row.put("indicators", p.risk().indicators().stream().map(FarmTools::indicator).toList());
				row.put("missingInputs", p.risk().missing());
				row.put("modelVersion", p.risk().modelVersion());
			}
			return row;
		}).toList());
		return out;
	}

	private Map<String, Object> activeAlerts(Context c) {
		List<AlertView> open = alerts.list(c.farm().getId(), c.user(), false);
		return Map.of("count", open.size(), "alerts", open.stream().limit(25).map((a) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("type", String.valueOf(a.type()));
			row.put("severity", String.valueOf(a.severity()));
			row.put("state", String.valueOf(a.state()));
			row.put("title", a.title());
			row.put("message", a.message());
			row.put("field", a.fieldName());
			row.put("asset", a.assetName());
			row.put("raised", local(c, a.createdAt()));
			row.put("source", (a.details() != null && "ML".equals(a.details().get("source"))) ? "ML model" : "rules");
			return compact(row);
		}).toList());
	}

	private Map<String, Object> searchKnowledge(Context c, String query) {
		SearchResult result = knowledge.search(query, 4);
		for (Passage passage : result.passages()) {
			for (Source source : passage.sources()) {
				c.sources().putIfAbsent(source.title(), source);
			}
		}
		if (result.passages().isEmpty()) {
			return Map.of("query", query, "note", "Nothing relevant in the knowledge base.");
		}
		return Map.of("query", query, "passages", result.passages().stream().map((p) -> Map.of("document",
				p.documentTitle(), "section", String.valueOf(p.heading()), "text", p.content(), "sources",
				p.sources().stream().map(FarmTools::sourceLabel).toList())).toList());
	}

	private Map<String, Object> report(Context c, int days) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("periodDays", days);
		out.put("summary", farmSummary(c));
		out.put("water", compareWater(c, days));
		out.put("recommendations", recommendations(c, null));
		out.put("alerts", activeAlerts(c));
		out.put("waterAnomalies", waterAnomalies(c));
		out.put("equipmentRisk", equipmentRisk(c, null));
		return out;
	}

	// ---- Helpers ----

	private List<FieldStatusView> status(Context c) {
		return irrigation.status(c.farm().getId(), c.user());
	}

	private FieldStatusView matchField(Context c, String query) {
		return match(query, status(c), FieldStatusView::fieldName, FieldStatusView::fieldId, "field");
	}

	/**
	 * Resolves a name the user typed: by id, exact name, then by distinctive words, so "Field A" finds
	 * "Block A · Tomatoes". Ambiguous or unknown names fail with the options, so the model can ask.
	 */
	static <T> T match(String query, List<T> items, Function<T, String> name, Function<T, UUID> id, String kind) {
		String q = query.strip();
		for (T item : items) {
			if (id.apply(item).toString().equalsIgnoreCase(q) || name.apply(item).equalsIgnoreCase(q)) {
				return item;
			}
		}
		List<String> wanted = words(q);
		List<T> hits = items.stream().filter((item) -> !wanted.isEmpty() && words(name.apply(item)).containsAll(wanted))
			.toList();
		if (hits.isEmpty()) {
			hits = items.stream()
				.filter((item) -> name.apply(item).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
				.toList();
		}
		if (hits.size() == 1) {
			return hits.get(0);
		}
		String options = items.stream().map(name).collect(Collectors.joining(", "));
		if (hits.isEmpty()) {
			throw new ToolException("No " + kind + " matches '" + q + "'. The farm's " + kind + "s are: " + options);
		}
		throw new ToolException("'" + q + "' matches several " + kind + "s: "
				+ hits.stream().map(name).collect(Collectors.joining(", ")) + ". Ask which one.");
	}

	private static List<String> words(String text) {
		List<String> out = new ArrayList<>();
		for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
			if (!word.isEmpty() && !GENERIC_WORDS.contains(word)) {
				out.add(word);
			}
		}
		return out;
	}

	private static Map<String, Object> fieldCore(FieldStatusView field) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("field", field.fieldName());
		out.put("areaHa", field.areaHa());
		out.put("crop", String.valueOf(field.cropType()));
		out.put("variety", field.variety());
		out.put("plantingDate", String.valueOf(field.plantingDate()));
		out.put("growthPhase", String.valueOf(field.phase()));
		out.put("daysAfterPlanting", field.daysAfterPlanting());
		out.put("irrigationMethod", String.valueOf(field.irrigationMethod()));
		out.put("pump", (field.pump() != null) ? field.pump().name() : null);
		out.put("depletionPct", field.depletionPct());
		out.put("stressThresholdPct", field.stressThresholdPct());
		out.put("availableWaterMm", field.tawMm());
		return out;
	}

	private static Map<String, Object> recommendation(RecommendationView r) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("decision", String.valueOf(r.decision()));
		out.put("waterStatus", String.valueOf(r.status()));
		out.put("irrigateOn", String.valueOf(r.irrigateOn()));
		out.put("daysUntilStress", r.daysUntilStress());
		out.put("grossDepthMm", r.grossDepthMm());
		out.put("volumeM3", r.volumeM3());
		out.put("durationMinutes", r.durationMinutes());
		if (r.schedule() != null) {
			out.put("startTime", String.valueOf(r.schedule().startTime()));
			out.put("sessions", r.schedule().sessions());
			out.put("timingReason", r.schedule().windowLabel());
		}
		out.put("energyKwh", r.energyKwh());
		out.put("energyCost", r.energyCost());
		out.put("expectedRainMm", r.expectedRainMm());
		out.put("reasons", r.reasons());
		out.put("confidence", String.valueOf(r.confidence()));
		out.put("confidenceNote", r.confidenceNote());
		out.put("method", "FAO-56 soil water balance (" + r.modelVersion() + ")");
		return out;
	}

	private Map<String, Object> modelFor(Context c, UUID fieldId) {
		return assessment(irrigationModel.assess(c.farm().getId(), c.user())
			.fields()
			.stream()
			.filter((f) -> f.fieldId().equals(fieldId))
			.findFirst()
			.orElse(null));
	}

	private static Map<String, Object> assessment(FieldAssessment a) {
		if (a == null || a.prediction() == null) {
			return Map.of("status", (a != null) ? a.status() : "UNAVAILABLE", "note",
					(a != null && "NO_CROP".equals(a.status())) ? "No active crop, so the model does not rate this field."
							: "The irrigation-need model is offline; rely on the water balance.");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("need", a.prediction().need());
		out.put("irrigationRequired", a.prediction().irrigationRequired());
		out.put("probabilityRequired", a.prediction().probabilityRequired());
		out.put("confidence", a.prediction().confidence());
		out.put("drivers", a.prediction().drivers().stream().map(FarmTools::driver).toList());
		out.put("inputs", compact(Map.of("soilMoisturePct", a.inputs().soilMoisturePct(), "soilMoistureSource",
				a.inputs().soilMoistureSource(), "temperatureC", a.inputs().temperatureC(), "windKmh",
				a.inputs().windSpeedKmh(), "rainLast90DaysMm", a.inputs().rainfallLast90DaysMm(), "growthPhase",
				String.valueOf(a.inputs().growthPhase()), "mulched", a.inputs().mulched())));
		if (!a.prediction().outOfRange().isEmpty()) {
			out.put("inputsOutsideTrainingRange", a.prediction().outOfRange());
		}
		if (a.agreement() != null) {
			out.put("agreesWithWaterBalance", a.agreement().consistent());
			out.put("agreementNote", a.agreement().note());
		}
		out.put("modelVersion", a.prediction().modelVersion());
		return out;
	}

	private Map<String, Object> probeFor(Context c, UUID fieldId) {
		DeviceView probe = devices.list(c.farm().getId(), c.user())
			.stream()
			.filter((d) -> d.type() == SensorType.SOIL_MOISTURE && fieldId.equals(d.fieldId()))
			.findFirst()
			.orElse(null);
		if (probe == null) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", probe.name());
		out.put("online", probe.online());
		out.put("lastSeen", local(c, probe.lastSeenAt()));
		if (probe.latest() != null) {
			out.put("readingPct", probe.latest().soilMoisturePct());
			out.put("readingAt", local(c, probe.latest().recordedAt()));
			out.put("depletionPct", probe.depletionPct());
		}
		out.put("calibration", (probe.fieldCapacityPct() != null)
				? "field capacity " + probe.fieldCapacityPct() + " %, wilting point " + probe.wiltingPointPct() + " %"
				: "default (field capacity 40 %, wilting point 15 %)");
		return out;
	}

	private Map<String, Object> event(Context c, EventView e) {
		return compact(Map.of("field", e.fieldName(), "status", String.valueOf(e.status()), "source",
				String.valueOf(e.source()), "start", local(c, e.startedAt()), "durationMinutes", e.durationMinutes(),
				"depthMm", String.valueOf(e.grossDepthMm()), "volumeM3", e.volumeM3(), "overdue", e.overdue()));
	}

	private static Map<String, Object> weatherDay(WeatherDay d) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("date", d.date());
		out.put("et0Mm", d.et0Mm());
		out.put("rainMm", d.precipitationMm());
		out.put("rainProbabilityPct", d.precipitationProbability());
		out.put("maxTempC", d.tempMaxC());
		out.put("minTempC", d.tempMinC());
		out.put("maxWindKmh", d.windMaxKmh());
		return compact(out);
	}

	private static Map<String, Object> factor(Factor f) {
		return Map.of("issue", f.label(), "detail", f.detail(), "fix", f.action(), "points", f.impact());
	}

	private static Map<String, Object> driver(Driver d) {
		return Map.of("factor", d.label(), "value", String.valueOf(d.value()), "typical", String.valueOf(d.typical()),
				"effect", d.direction(), "impact", d.impact());
	}

	private static Map<String, Object> indicator(Indicator i) {
		return Map.of("reading", i.label(), "value", i.value(), "healthy", i.healthy(), "impact", i.impact());
	}

	static String sourceLabel(Source source) {
		return source.title() + ((source.publisher() != null) ? " (" + source.publisher() + ")" : "");
	}

	private static String local(Context c, Instant instant) {
		if (instant == null) {
			return null;
		}
		ZoneId zone = c.farm().zone();
		return LOCAL.format(instant.atZone(zone));
	}

	private static String text(Map<String, Object> input, String key) {
		Object value = input.get(key);
		return (value instanceof String s && !s.isBlank()) ? s.strip() : null;
	}

	private static int integer(Map<String, Object> input, String key, int fallback) {
		return (input.get(key) instanceof Number n) ? n.intValue() : fallback;
	}

	private static Double pct(double now, double before) {
		return (before > 0) ? r1(100 * (now - before) / before) : null;
	}

	private static double r1(double value) {
		return Math.round(value * 10) / 10.0;
	}

	/** Drops null and "null" values so the model never sees placeholders it might repeat. */
	private static Map<String, Object> compact(Map<String, Object> map) {
		Map<String, Object> out = new LinkedHashMap<>();
		map.forEach((key, value) -> {
			if (value != null && !Objects.equals(value, "null")) {
				out.put(key, value);
			}
		});
		return out;
	}

	private static ChatModel.ToolSpec spec(String name, String description, Map<String, Object> properties,
			List<String> required) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("required", required);
		schema.put("additionalProperties", false);
		return new ChatModel.ToolSpec(name, description, schema);
	}

	private static Map<String, Object> fieldParam(boolean required) {
		return Map.of("type", "string", "minLength", 1, "maxLength", 160, "description",
				required ? "The field's name as the user said it, or its id"
						: "Only when the user names one field: its name or id. Leave out to cover every field.");
	}

	private static Map<String, Object> assetParam() {
		return Map.of("type", "string", "minLength", 1, "maxLength", 160, "description",
				"Only when the user names one asset: its name. Leave out to cover every asset.");
	}

	private static Map<String, Object> intParam(String description, int min, int max) {
		return Map.of("type", "integer", "minimum", min, "maximum", max, "description", description);
	}

}
