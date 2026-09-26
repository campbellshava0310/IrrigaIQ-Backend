package com.irrigaiq.agronomy;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.irrigaiq.farm.CropType;
import com.irrigaiq.farm.IrrigationMethod;
import com.irrigaiq.farm.SoilType;

/**
 * Daily root-zone soil water balance (FAO-56 chapter 8) driven by reference evapotranspiration
 * and rainfall, with irrigation and field observations as inputs.
 *
 * <pre>
 * Dr(i) = Dr(i-1) - Peff(i) - Inet(i) + Ks(i) * Kc(i) * ET0(i),   0 <= Dr <= TAW
 * TAW = available water x root depth,   RAW = p x TAW,   Ks = (TAW - Dr) / ((1 - p) TAW) when Dr > RAW
 * </pre>
 *
 * Pure and deterministic: no I/O, so it runs identically in the API, in tests and for the demo
 * data generator. Soil-moisture sensors and the ML model later feed observations and replace the
 * recommendation policy; the balance itself stays the explainable baseline.
 */
public final class WaterBalanceEngine {

	/** How far back the simulation goes when a crop was planted long ago. */
	public static final int MAX_HISTORY_DAYS = 180;

	/** Assumed depletion when the simulation cannot start at planting (unknown history). */
	static final double UNKNOWN_START_DEPLETION = 0.35;

	/** Depth below which an irrigation is not worth running. */
	static final double MIN_USEFUL_NET_DEPTH_MM = 3.0;

	/** Share of the deficit that forecast rain must cover to hold irrigation. */
	static final double RAIN_HOLD_COVERAGE = 0.6;

	static final double DEFAULT_ET0_MM = 4.5;

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

	private WaterBalanceEngine() {
	}

	// ---- Inputs ----

	public record WeatherInput(LocalDate date, Double et0Mm, Double precipitationMm, Integer precipitationProbability,
			boolean forecast) {
	}

	public record IrrigationInput(LocalDate date, double grossDepthMm, boolean planned) {
	}

	/**
	 * @param irrigationAppliedMm gross depth irrigated earlier the same day, already reflected in the reading
	 */
	public record ObservationInput(LocalDate date, double depletionPct, double irrigationAppliedMm) {

		public ObservationInput(LocalDate date, double depletionPct) {
			this(date, depletionPct, 0);
		}

	}

	public record CropInput(CropType type, LocalDate plantingDate, Double expectedYieldTHa) {
	}

	public record Input(LocalDate today, double areaHa, SoilType soilType, CropInput crop, IrrigationMethod method,
			Double applicationRateMmH, Double efficiency, List<WeatherInput> weather, List<IrrigationInput> irrigations,
			List<ObservationInput> observations) {
	}

	// ---- Outputs ----

	public record Day(LocalDate date, boolean forecast, int daysAfterPlanting, GrowthPhase phase, double kc,
			double et0Mm, double etcMm, double etaMm, double precipitationMm, double effectiveRainMm,
			double irrigationGrossMm, double plannedIrrigationGrossMm, double depletionStartMm, double depletionEndMm,
			double tawMm, double rawMm, double ks) {
	}

	public enum Decision {

		NOT_PLANTED, SEASON_ENDED, IRRIGATE_NOW, IRRIGATE_SOON, HOLD_FOR_RAIN, SCHEDULED, ON_TRACK

	}

	public enum WaterStatus {

		NOT_PLANTED, SEASON_ENDED, ADEQUATE, IRRIGATE_SOON, IRRIGATE_NOW, STRESSED

	}

	public record Recommendation(Decision decision, WaterStatus status, LocalDate irrigateOn, Integer daysUntilStress,
			double netDepthMm, double grossDepthMm, double volumeM3, int durationMinutes, double expectedRainMm,
			List<String> reasons) {
	}

	public record SeasonStats(int daysAfterPlanting, int seasonLengthDays, GrowthPhase phase, double kc,
			double etcMm, double etaMm, double effectiveRainMm, double irrigationGrossMm, int stressDays,
			double relativeEt, double yieldReductionPct, double attainableYieldTHa, double expectedYieldTHa) {
	}

	public record Result(List<Day> days, double currentDepletionMm, double currentTawMm, double currentRawMm,
			double efficiency, double applicationRateMmH, Recommendation recommendation, SeasonStats season) {

		public double depletionPct() {
			return (currentTawMm > 0) ? 100.0 * currentDepletionMm / currentTawMm : 0;
		}

		public double stressThresholdPct() {
			return (currentTawMm > 0) ? 100.0 * currentRawMm / currentTawMm : 0;
		}

	}

	// ---- Engine ----

	private static final ObservationInput NO_OBSERVATION = new ObservationInput(LocalDate.MIN, 0);

	public static Result run(Input input) {
		IrrigationMethod method = (input.method() != null) ? input.method() : IrrigationMethod.DEFAULT;
		double efficiency = (input.efficiency() != null) ? input.efficiency() : method.defaultEfficiency();
		double rate = (input.applicationRateMmH() != null) ? input.applicationRateMmH()
				: method.defaultApplicationRateMmH();
		CropInput crop = input.crop();
		LocalDate today = input.today();

		if (crop == null || crop.plantingDate() == null || crop.plantingDate().isAfter(today)) {
			Recommendation none = new Recommendation(Decision.NOT_PLANTED, WaterStatus.NOT_PLANTED, null, null, 0, 0, 0,
					0, 0, List.of("No active crop on this field. Add a crop with its planting date to get recommendations."));
			return new Result(List.of(), 0, 0, 0, efficiency, rate, none, null);
		}

		CropParameters params = CropCatalog.parameters(crop.type());
		double availableWater = SoilCatalog.availableWaterMmPerM(input.soilType());
		List<Day> days = simulate(input, params, availableWater, efficiency);

		Day todayRow = days.stream().filter((day) -> day.date().equals(today)).findFirst().orElseThrow();
		double alreadyObserved = observationsByDate(input).getOrDefault(today, NO_OBSERVATION).irrigationAppliedMm();
		double completedNetToday = Math.max(0,
				todayRow.irrigationGrossMm() - todayRow.plannedIrrigationGrossMm() - alreadyObserved) * efficiency;
		double current = Math.max(0, todayRow.depletionStartMm() - completedNetToday);

		SeasonStats season = seasonStats(days, today, params, crop);
		Recommendation recommendation = recommend(days, todayRow, current, params, crop.type(), method, efficiency,
				rate, input.areaHa(), today);
		return new Result(days, current, todayRow.tawMm(), todayRow.rawMm(), efficiency, rate, recommendation, season);
	}

	private static List<Day> simulate(Input input, CropParameters params, double availableWater, double efficiency) {
		LocalDate today = input.today();
		LocalDate planting = input.crop().plantingDate();
		LocalDate start = planting;
		boolean unknownStart = false;
		if (start.isBefore(today.minusDays(MAX_HISTORY_DAYS))) {
			start = today.minusDays(MAX_HISTORY_DAYS);
			unknownStart = true;
		}

		Map<LocalDate, WeatherInput> weather = new HashMap<>();
		LocalDate end = today;
		for (WeatherInput day : input.weather()) {
			weather.put(day.date(), day);
			if (day.date().isAfter(end)) {
				end = day.date();
			}
		}
		Map<LocalDate, double[]> irrigation = new HashMap<>(); // [completed, planned]
		for (IrrigationInput event : input.irrigations()) {
			if (event.planned() && event.date().isBefore(today)) {
				continue; // a past plan that nobody confirmed did not water the field
			}
			double[] totals = irrigation.computeIfAbsent(event.date(), (d) -> new double[2]);
			totals[event.planned() ? 1 : 0] += event.grossDepthMm();
		}
		Map<LocalDate, ObservationInput> observations = observationsByDate(input);

		List<Day> days = new ArrayList<>();
		double depletion = Double.NaN;
		double lastEt0 = DEFAULT_ET0_MM;
		for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
			int dap = (int) ChronoUnit.DAYS.between(planting, date);
			double kc = params.kcAt(dap);
			double taw = availableWater * params.rootDepthAt(dap);

			WeatherInput w = weather.get(date);
			double et0 = (w != null && w.et0Mm() != null) ? w.et0Mm() : lastEt0;
			lastEt0 = et0;
			double precipitation = (w != null && w.precipitationMm() != null) ? w.precipitationMm() : 0;
			boolean forecast = (w != null) ? w.forecast() : date.isAfter(today);

			double potentialEtc = kc * et0;
			double p = Math.max(0.1, Math.min(0.8, params.depletionFraction() + 0.04 * (5 - potentialEtc)));
			double raw = p * taw;

			if (Double.isNaN(depletion)) {
				depletion = unknownStart ? UNKNOWN_START_DEPLETION * taw : 0;
			}
			ObservationInput observed = observations.get(date);
			if (observed != null) {
				depletion = observed.depletionPct() / 100.0 * taw;
			}
			depletion = Math.min(depletion, taw);
			double depletionStart = depletion;

			double ks = (depletion <= raw) ? 1.0 : Math.max(0, (taw - depletion) / ((1 - p) * taw));
			double eta = ks * potentialEtc;
			double effectiveRain = effectiveRain(precipitation);
			if (forecast && w != null && w.precipitationProbability() != null) {
				effectiveRain *= w.precipitationProbability() / 100.0; // expected value of uncertain rain
			}
			double[] irrigationToday = irrigation.getOrDefault(date, new double[2]);
			double gross = irrigationToday[0] + irrigationToday[1];
			// Water applied before a same-day soil check is already in the observed value.
			double alreadyObserved = (observed != null) ? Math.min(observed.irrigationAppliedMm(), irrigationToday[0]) : 0;

			depletion = depletion - effectiveRain - (gross - alreadyObserved) * efficiency + eta;
			depletion = Math.max(0, Math.min(taw, depletion)); // excess drains below the root zone

			days.add(new Day(date, forecast, dap, params.phaseAt(dap), kc, et0, potentialEtc, eta, precipitation,
					effectiveRain, gross, irrigationToday[1], depletionStart, depletion, taw, raw, ks));
		}
		return days;
	}

	private static Map<LocalDate, ObservationInput> observationsByDate(Input input) {
		Map<LocalDate, ObservationInput> observations = new TreeMap<>();
		for (ObservationInput observation : input.observations()) {
			observations.put(observation.date(), observation); // latest per day wins (input is ordered)
		}
		return observations;
	}

	/** Rain lost to interception (first 2 mm) and runoff/evaporation (20 %) does not reach roots. */
	static double effectiveRain(double precipitationMm) {
		return 0.8 * Math.max(0, precipitationMm - 2.0);
	}

	private static Recommendation recommend(List<Day> days, Day todayRow, double current, CropParameters params,
			CropType cropType, IrrigationMethod method, double efficiency, double rate, double areaHa,
			LocalDate today) {
		List<String> reasons = new ArrayList<>();
		String cropName = humanize(cropType.name());
		if (todayRow.phase() == GrowthPhase.HARVEST) {
			reasons.add(cropName + " has completed its " + params.seasonLengthDays()
					+ "-day season. Stop irrigating ahead of harvest and record the next crop.");
			return new Recommendation(Decision.SEASON_ENDED, WaterStatus.SEASON_ENDED, null, null, 0, 0, 0, 0, 0,
					reasons);
		}

		double taw = todayRow.tawMm();
		double raw = todayRow.rawMm();
		List<Day> ahead = days.stream().filter((day) -> !day.date().isBefore(today)).toList();

		// First day the projection (including planned irrigation) crosses the stress threshold.
		Integer daysUntilStress = null;
		if (current >= raw) {
			daysUntilStress = 0;
		}
		else {
			for (Day day : ahead) {
				if (day.depletionEndMm() >= day.rawMm()) {
					daysUntilStress = (int) ChronoUnit.DAYS.between(today, day.date());
					break;
				}
			}
		}

		WaterStatus status;
		if (current >= raw + 0.5 * (taw - raw)) {
			status = WaterStatus.STRESSED;
		}
		else if (daysUntilStress != null && daysUntilStress == 0) {
			status = WaterStatus.IRRIGATE_NOW;
		}
		else if (daysUntilStress != null && daysUntilStress <= 2) {
			status = WaterStatus.IRRIGATE_SOON;
		}
		else {
			status = WaterStatus.ADEQUATE;
		}

		double expectedRain = ahead.stream().limit(2).mapToDouble(Day::effectiveRainMm).sum();
		boolean plannedSoon = ahead.stream().limit(2).anyMatch((day) -> day.plannedIrrigationGrossMm() > 0);
		List<Day> past = days.stream().filter((day) -> day.date().isBefore(today)).toList();
		double avgEtc = past.subList(Math.max(0, past.size() - 3), past.size())
			.stream()
			.mapToDouble(Day::etcMm)
			.average()
			.orElse(todayRow.etcMm());

		reasons.add(String.format(Locale.ENGLISH, "Root zone is %.0f%% depleted; %s in %s stage starts to stress at %.0f%%.",
				100 * current / taw, cropName, humanize(todayRow.phase().name()).toLowerCase(Locale.ENGLISH),
				100 * raw / taw));
		reasons.add(String.format(Locale.ENGLISH, "Crop water use is running at %.1f mm/day (Kc %.2f).", avgEtc,
				todayRow.kc()));

		// Where and how much to irrigate if we act.
		LocalDate irrigateOn;
		double deficitAtIrrigation;
		if (daysUntilStress == null) {
			irrigateOn = null;
			deficitAtIrrigation = current;
		}
		else if (daysUntilStress == 0) {
			irrigateOn = today;
			deficitAtIrrigation = current;
		}
		else {
			Day target = ahead.get(daysUntilStress);
			irrigateOn = target.date();
			deficitAtIrrigation = target.depletionStartMm();
		}

		Decision decision;
		if (plannedSoon) {
			decision = Decision.SCHEDULED;
			reasons.add("An irrigation is already planned within the next 48 hours.");
		}
		else if (status != WaterStatus.STRESSED && irrigateOn != null && daysUntilStress <= 2
				&& expectedRain >= RAIN_HOLD_COVERAGE * deficitAtIrrigation) {
			decision = Decision.HOLD_FOR_RAIN;
			reasons.add(String.format(Locale.ENGLISH,
					"About %.0f mm of effective rain is expected in the next 48 h, covering most of the %.0f mm deficit. Re-check after the rain.",
					expectedRain, deficitAtIrrigation));
		}
		else if (status == WaterStatus.STRESSED || status == WaterStatus.IRRIGATE_NOW) {
			decision = Decision.IRRIGATE_NOW;
			if (status == WaterStatus.STRESSED) {
				reasons.add(String.format(Locale.ENGLISH,
						"The crop is already water-stressed (Ks %.2f): every day without water reduces yield.",
						Math.max(0, (taw - current) / (taw - raw))));
			}
		}
		else if (status == WaterStatus.IRRIGATE_SOON) {
			decision = Decision.IRRIGATE_SOON;
			reasons.add("Projected to reach the stress threshold on " + DAY.format(irrigateOn) + ".");
		}
		else {
			decision = Decision.ON_TRACK;
			reasons.add((irrigateOn != null)
					? "Next irrigation needed around " + DAY.format(irrigateOn) + "."
					: "Soil water is sufficient for the whole forecast window.");
		}
		if (decision != Decision.HOLD_FOR_RAIN) {
			reasons.add((expectedRain >= 1)
					? String.format(Locale.ENGLISH, "About %.0f mm of effective rain is expected in the next 48 h.",
							expectedRain)
					: "No meaningful rain is expected in the next 48 h.");
		}

		double net = (irrigateOn != null) ? deficitAtIrrigation : 0;
		if (net < MIN_USEFUL_NET_DEPTH_MM) {
			net = 0;
		}
		if (net > method.maxNetApplicationMm()) {
			reasons.add(String.format(Locale.ENGLISH,
					"%s applies at most about %.0f mm per pass without runoff: apply that now and repeat as the soil dries (a full refill needs %.0f mm).",
					humanize(method.name()), method.maxNetApplicationMm(), net));
			net = method.maxNetApplicationMm();
		}
		double gross = net / efficiency;
		double volumeM3 = gross * areaHa * 10.0;
		int durationMinutes = (gross > 0) ? (int) Math.ceil(gross / rate * 60.0) : 0;
		return new Recommendation(decision, status, irrigateOn, daysUntilStress, net, gross, volumeM3,
				durationMinutes, expectedRain, List.copyOf(reasons));
	}

	private static SeasonStats seasonStats(List<Day> days, LocalDate today, CropParameters params, CropInput crop) {
		double etc = 0;
		double eta = 0;
		double rain = 0;
		double irrigation = 0;
		int stressDays = 0;
		for (Day day : days) {
			if (day.date().isAfter(today)) {
				break;
			}
			etc += day.etcMm();
			eta += day.etaMm();
			rain += day.effectiveRainMm();
			irrigation += day.irrigationGrossMm() - day.plannedIrrigationGrossMm();
			if (day.ks() < 0.999) {
				stressDays++;
			}
		}
		double relativeEt = (etc > 0) ? Math.min(1.0, eta / etc) : 1.0;
		double reductionPct = Math.max(0, Math.min(100, params.yieldResponseKy() * (1 - relativeEt) * 100));
		double attainable = (crop.expectedYieldTHa() != null) ? crop.expectedYieldTHa() : params.typicalYieldTHa();
		int dap = (int) ChronoUnit.DAYS.between(crop.plantingDate(), today);
		return new SeasonStats(dap, params.seasonLengthDays(), params.phaseAt(dap), params.kcAt(dap), etc, eta, rain,
				irrigation, stressDays, relativeEt, reductionPct, attainable, attainable * (1 - reductionPct / 100));
	}

	private static String humanize(String value) {
		String text = value.toLowerCase(Locale.ENGLISH).replace('_', ' ');
		return Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

}
