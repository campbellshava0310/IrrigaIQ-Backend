package com.irrigaiq.irrigation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.irrigaiq.agronomy.CropCatalog;
import com.irrigaiq.agronomy.CropParameters;
import com.irrigaiq.agronomy.SoilCatalog;
import com.irrigaiq.agronomy.WaterBalanceEngine.Day;
import com.irrigaiq.agronomy.WaterBalanceEngine.Recommendation;
import com.irrigaiq.agronomy.WaterBalanceEngine.Result;
import com.irrigaiq.agronomy.WaterBalanceEngine;
import com.irrigaiq.assets.Asset;
import com.irrigaiq.assets.AssetRepository;
import com.irrigaiq.assets.AssetService;
import com.irrigaiq.assets.AssetType;
import com.irrigaiq.assets.PowerSource;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.common.events.FarmDataChanged;
import com.irrigaiq.common.text.HumanText;
import com.irrigaiq.farm.Crop;
import com.irrigaiq.farm.CropRepository;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.farm.FarmViews.FieldView;
import com.irrigaiq.farm.Field;
import com.irrigaiq.farm.FieldRepository;
import com.irrigaiq.farm.FieldService;
import com.irrigaiq.farm.IrrigationMethod;
import com.irrigaiq.irrigation.IrrigationViews.Confidence;
import com.irrigaiq.irrigation.IrrigationViews.DayView;
import com.irrigaiq.irrigation.IrrigationViews.EventView;
import com.irrigaiq.irrigation.IrrigationViews.FieldStatusView;
import com.irrigaiq.irrigation.IrrigationViews.ObservationView;
import com.irrigaiq.irrigation.IrrigationViews.PumpRef;
import com.irrigaiq.irrigation.IrrigationViews.RecommendationView;
import com.irrigaiq.irrigation.IrrigationViews.ScheduleView;
import com.irrigaiq.irrigation.IrrigationViews.SeasonView;
import com.irrigaiq.irrigation.IrrigationViews.SetupView;
import com.irrigaiq.irrigation.IrrigationViews.WaterBalanceView;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.weather.WeatherDay;
import com.irrigaiq.weather.WeatherService.WeatherSeries;
import com.irrigaiq.weather.WeatherService;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Irrigation decisions per field: runs the soil-water balance on live weather, turns the result
 * into a schedule that respects each pump's power window, and tracks what was actually applied.
 */
@Service
public class IrrigationService {

	public static final String RECOMMENDER = "FAO56_WATER_BALANCE";

	public static final String RECOMMENDER_VERSION = "fao56-1";

	static final int FORECAST_DAYS = 7;

	static final int DEFAULT_HISTORY_DAYS = 30;

	/** Crops stay "active" this long past their nominal season, in case harvest runs late. */
	static final int SEASON_GRACE_DAYS = 30;

	private final FarmService farms;

	private final FieldService fieldService;

	private final FieldRepository fields;

	private final CropRepository crops;

	private final IrrigationEventRepository events;

	private final SoilObservationRepository observations;

	private final WeatherService weather;

	private final AssetService assetService;

	private final AssetRepository assets;

	private final ApplicationEventPublisher publisher;

	private final Clock clock;

	public IrrigationService(FarmService farms, FieldService fieldService, FieldRepository fields, CropRepository crops,
			IrrigationEventRepository events, SoilObservationRepository observations, WeatherService weather,
			AssetService assetService, AssetRepository assets, ApplicationEventPublisher publisher, Clock clock) {
		this.farms = farms;
		this.fieldService = fieldService;
		this.fields = fields;
		this.crops = crops;
		this.events = events;
		this.observations = observations;
		this.weather = weather;
		this.assetService = assetService;
		this.assets = assets;
		this.publisher = publisher;
		this.clock = clock;
	}

	/** Everything known about one field's water situation. */
	public record FieldWaterState(Field field, Crop crop, CropParameters parameters, Result result, Asset pump,
			ScheduleView schedule, Double energyKwh, Double energyCost, Confidence confidence, String confidenceNote,
			IrrigationEvent nextPlanned, IrrigationEvent lastCompleted, boolean weatherLive, boolean locationApproximate,
			LocalDate today) {

		public Recommendation recommendation() {
			return result.recommendation();
		}

	}

	// ---- Queries ----

	@Transactional(readOnly = true)
	public List<FieldStatusView> status(UUID farmId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		return evaluate(farm).stream().map((state) -> statusView(state, farm)).toList();
	}

	@Transactional(readOnly = true)
	public WaterBalanceView waterBalance(UUID farmId, UUID fieldId, AuthenticatedUser user, int historyDays) {
		Farm farm = farms.requireAccessible(farmId, user);
		Field field = fieldService.requireAccessible(farmId, fieldId, user);
		FieldWaterState state = evaluate(farm, List.of(field), historyDays).get(0);
		LocalDate firstShown = state.today().minusDays(historyDays);
		List<DayView> days = state.result()
			.days()
			.stream()
			.filter((day) -> !day.date().isBefore(firstShown))
			.map(IrrigationService::dayView)
			.toList();
		return new WaterBalanceView(state.today(), statusView(state, farm), seasonView(state), setupView(state), days,
				state.weatherLive(), state.locationApproximate());
	}

	@Transactional(readOnly = true)
	public List<EventView> events(UUID farmId, AuthenticatedUser user, LocalDate from, LocalDate to, UUID fieldId,
			IrrigationStatus status) {
		Farm farm = farms.requireAccessible(farmId, user);
		ZoneId zone = farm.zone();
		return events.findByFarmBetween(farmId, from.atStartOfDay(zone).toInstant(),
				to.plusDays(1).atStartOfDay(zone).toInstant())
			.stream()
			.filter((event) -> fieldId == null || event.getField().getId().equals(fieldId))
			.filter((event) -> status == null || event.getStatus() == status)
			.map(this::eventView)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<ObservationView> observations(UUID farmId, UUID fieldId, AuthenticatedUser user) {
		fieldService.requireAccessible(farmId, fieldId, user);
		return observations.findByFieldIdOrderByObservedAtDesc(fieldId, PageRequest.of(0, 50))
			.stream()
			.map(ObservationView::from)
			.toList();
	}

	// ---- Commands ----

	public record IrrigationSetup(IrrigationMethod method, BigDecimal applicationRateMmH, BigDecimal efficiency,
			UUID pumpAssetId) {
	}

	@Transactional
	public FieldView configure(UUID farmId, UUID fieldId, AuthenticatedUser user, IrrigationSetup setup) {
		Field field = fieldService.requireAccessible(farmId, fieldId, user);
		if (setup.pumpAssetId() != null) {
			requirePump(farmId, setup.pumpAssetId());
		}
		field.configureIrrigation(setup.method(), setup.applicationRateMmH(), setup.efficiency(), setup.pumpAssetId());
		fields.flush();
		publisher.publishEvent(new FarmDataChanged(farmId));
		return FieldView.from(field);
	}

	/**
	 * @param grossDepthMm depth applied over the field; derived from volume or run time when absent
	 */
	public record LogRequest(IrrigationStatus status, IrrigationSource source, Instant startedAt, int durationMinutes,
			Double grossDepthMm, Double volumeM3, UUID pumpAssetId, Double energyKwh, String notes) {
	}

	@Transactional
	public EventView log(UUID farmId, UUID fieldId, AuthenticatedUser user, LogRequest request) {
		Farm farm = farms.requireAccessible(farmId, user);
		Field field = fieldService.requireAccessible(farmId, fieldId, user);
		IrrigationStatus status = (request.status() != null) ? request.status() : IrrigationStatus.COMPLETED;
		if (status != IrrigationStatus.PLANNED && status != IrrigationStatus.COMPLETED) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "New irrigations are either planned or completed");
		}
		Instant now = clock.instant();
		if (status == IrrigationStatus.COMPLETED && request.startedAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "A completed irrigation cannot start in the future");
		}
		UUID pumpId = (request.pumpAssetId() != null) ? request.pumpAssetId() : field.getPumpAssetId();
		Asset pump = (pumpId != null) ? requirePump(farmId, pumpId) : null;

		double depth = depthFor(field, request.grossDepthMm(), request.volumeM3(), request.durationMinutes());
		Double energy = (request.energyKwh() != null) ? request.energyKwh() : estimateEnergy(pump, request.durationMinutes());
		IrrigationEvent event = events.save(new IrrigationEvent(field, status,
				(request.source() != null) ? request.source() : IrrigationSource.MANUAL, request.startedAt(),
				request.durationMinutes(), depth, (pump != null) ? pump.getId() : null, energy, request.notes(),
				user.id(), now));
		if (status == IrrigationStatus.COMPLETED && pump != null) {
			assetService.addRunHours(pump.getId(), request.durationMinutes() / 60.0);
		}
		publisher.publishEvent(new FarmDataChanged(farm.getId()));
		return eventView(event);
	}

	/** One tap from "Irrigate Field 02 today" to a planned irrigation in the pump's power window. */
	@Transactional
	public EventView planRecommended(UUID farmId, UUID fieldId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		Field field = fieldService.requireAccessible(farmId, fieldId, user);
		FieldWaterState state = evaluate(farm, List.of(field), 1).get(0);
		Recommendation recommendation = state.recommendation();
		if (recommendation.irrigateOn() == null || recommendation.grossDepthMm() <= 0) {
			throw ApiException.conflict(ErrorCode.OPERATION_NOT_ALLOWED,
					"There is no irrigation to schedule for this field right now");
		}
		Instant start = recommendation.irrigateOn().atTime(state.schedule().startTime()).atZone(farm.zone()).toInstant();
		Instant earliest = clock.instant().plus(Duration.ofMinutes(15));
		if (start.isBefore(earliest)) {
			start = earliest.truncatedTo(ChronoUnit.MINUTES);
		}
		IrrigationEvent event = events.save(new IrrigationEvent(field, IrrigationStatus.PLANNED,
				IrrigationSource.RECOMMENDATION, start, recommendation.durationMinutes(), recommendation.grossDepthMm(),
				(state.pump() != null) ? state.pump().getId() : null, state.energyKwh(),
				"Planned from the water-balance recommendation", user.id(), clock.instant()));
		publisher.publishEvent(new FarmDataChanged(farmId));
		return eventView(event);
	}

	public record CompleteRequest(Instant startedAt, Integer durationMinutes, Double volumeM3, Double energyKwh,
			String notes) {
	}

	@Transactional
	public EventView complete(UUID farmId, UUID eventId, AuthenticatedUser user, CompleteRequest request) {
		farms.requireAccessible(farmId, user);
		IrrigationEvent event = requireEvent(farmId, eventId);
		if (event.getStatus() != IrrigationStatus.PLANNED) {
			throw ApiException.conflict(ErrorCode.OPERATION_NOT_ALLOWED, "Only planned irrigations can be completed");
		}
		event.complete(request.startedAt(), request.durationMinutes(),
				(request.volumeM3() != null) ? request.volumeM3() * 1000 : null, request.energyKwh(), request.notes(),
				clock.instant());
		if (event.getPumpAssetId() != null) {
			Asset pump = assets.findById(event.getPumpAssetId()).orElse(null);
			event.estimateEnergy(estimateEnergy(pump, event.getDurationMinutes()));
			assetService.addRunHours(event.getPumpAssetId(), event.getDurationMinutes() / 60.0);
		}
		publisher.publishEvent(new FarmDataChanged(farmId));
		return eventView(event);
	}

	@Transactional
	public EventView skip(UUID farmId, UUID eventId, AuthenticatedUser user, String reason) {
		farms.requireAccessible(farmId, user);
		IrrigationEvent event = requireEvent(farmId, eventId);
		if (event.getStatus() != IrrigationStatus.PLANNED) {
			throw ApiException.conflict(ErrorCode.OPERATION_NOT_ALLOWED, "Only planned irrigations can be skipped");
		}
		event.skip(reason);
		publisher.publishEvent(new FarmDataChanged(farmId));
		return eventView(event);
	}

	/** Removes a mistaken record; a completed irrigation also gives back its pump run-hours. */
	@Transactional
	public void delete(UUID farmId, UUID eventId, AuthenticatedUser user) {
		farms.requireAccessible(farmId, user);
		IrrigationEvent event = requireEvent(farmId, eventId);
		if (event.getStatus() == IrrigationStatus.COMPLETED && event.getPumpAssetId() != null) {
			assetService.removeRunHours(event.getPumpAssetId(), event.getDurationMinutes() / 60.0);
		}
		events.delete(event);
		publisher.publishEvent(new FarmDataChanged(farmId));
	}

	public record ObservationRequest(Instant observedAt, double depletionPct, ObservationMethod method, String notes) {
	}

	@Transactional
	public ObservationView observe(UUID farmId, UUID fieldId, AuthenticatedUser user, ObservationRequest request) {
		Field field = fieldService.requireAccessible(farmId, fieldId, user);
		Instant now = clock.instant();
		Instant observedAt = (request.observedAt() != null) ? request.observedAt() : now;
		if (observedAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "An observation cannot be in the future");
		}
		SoilObservation observation = observations.save(new SoilObservation(field, observedAt, request.depletionPct(),
				(request.method() != null) ? request.method() : ObservationMethod.FEEL, request.notes(), user.id(), now));
		publisher.publishEvent(new FarmDataChanged(farmId));
		return ObservationView.from(observation);
	}

	// ---- Module API (farm already authorised) ----

	@Transactional(readOnly = true)
	public List<FieldWaterState> evaluate(Farm farm) {
		return evaluate(farm, DEFAULT_HISTORY_DAYS);
	}

	/** @param historyDays minimum simulated history, for callers that report on longer periods */
	@Transactional(readOnly = true)
	public List<FieldWaterState> evaluate(Farm farm, int historyDays) {
		return evaluate(farm, fields.findByFarmIdOrderByNameAsc(farm.getId()), historyDays);
	}

	@Transactional(readOnly = true)
	public List<IrrigationEvent> eventsBetween(Farm farm, Instant from, Instant to) {
		return events.findByFarmBetween(farm.getId(), from, to);
	}

	// ---- Evaluation ----

	private List<FieldWaterState> evaluate(Farm farm, List<Field> targetFields, int historyDays) {
		if (targetFields.isEmpty()) {
			return List.of();
		}
		ZoneId zone = farm.zone();
		LocalDate today = weather.today(farm);

		Map<UUID, Crop> activeCrops = activeCrops(farm, today);
		LocalDate from = today.minusDays(Math.max(historyDays, 1));
		for (Field field : targetFields) {
			Crop crop = activeCrops.get(field.getId());
			if (crop != null) {
				LocalDate simulationStart = crop.getPlantingDate().isBefore(today.minusDays(WaterBalanceEngine.MAX_HISTORY_DAYS))
						? today.minusDays(WaterBalanceEngine.MAX_HISTORY_DAYS) : crop.getPlantingDate();
				if (simulationStart.isBefore(from)) {
					from = simulationStart;
				}
			}
		}
		LocalDate to = today.plusDays(FORECAST_DAYS);
		WeatherSeries series = weather.series(farm, from, to);
		List<WaterBalanceEngine.WeatherInput> weatherInputs = series.days()
			.stream()
			.map(IrrigationService::weatherInput)
			.toList();

		Instant windowStart = from.atStartOfDay(zone).toInstant();
		Map<UUID, List<IrrigationEvent>> eventsByField = events
			.findByFarmBetween(farm.getId(), windowStart, to.plusDays(8).atStartOfDay(zone).toInstant())
			.stream()
			.collect(Collectors.groupingBy((event) -> event.getField().getId()));
		Map<UUID, List<SoilObservation>> observationsByField = observations.findByFarmSince(farm.getId(), windowStart)
			.stream()
			.collect(Collectors.groupingBy((observation) -> observation.getField().getId()));
		Map<UUID, Asset> pumps = assets.findByFarmIdOrderByNameAsc(farm.getId())
			.stream()
			.filter((asset) -> asset.getType() == AssetType.PUMP)
			.collect(Collectors.toMap(Asset::getId, (asset) -> asset));

		List<FieldWaterState> states = new ArrayList<>(targetFields.size());
		for (Field field : targetFields) {
			Crop crop = activeCrops.get(field.getId());
			List<IrrigationEvent> fieldEvents = eventsByField.getOrDefault(field.getId(), List.of());
			List<SoilObservation> fieldObservations = observationsByField.getOrDefault(field.getId(), List.of());

			WaterBalanceEngine.Input input = new WaterBalanceEngine.Input(today,
					(field.getAreaHa() != null) ? field.getAreaHa().doubleValue() : 1.0, field.getSoilType(),
					(crop != null) ? new WaterBalanceEngine.CropInput(crop.getCropType(), crop.getPlantingDate(),
							(crop.getExpectedYieldTHa() != null) ? crop.getExpectedYieldTHa().doubleValue() : null)
							: null,
					field.getIrrigationMethod(), toDouble(field.getApplicationRateMmH()),
					toDouble(field.getSystemEfficiency()), weatherInputs, irrigationInputs(fieldEvents, zone),
					observationInputs(fieldObservations, fieldEvents, zone));
			Result result = WaterBalanceEngine.run(input);

			Asset pump = (field.getPumpAssetId() != null) ? pumps.get(field.getPumpAssetId()) : null;
			int duration = result.recommendation().durationMinutes();
			ScheduleView schedule = suggestSchedule(pump,
					(field.getIrrigationMethod() != null) ? field.getIrrigationMethod() : IrrigationMethod.DEFAULT,
					duration);
			Double energy = estimateEnergy(pump, duration);
			Double cost = energyCost(farm, pump, energy);

			Instant now = clock.instant();
			IrrigationEvent nextPlanned = fieldEvents.stream()
				.filter((event) -> event.getStatus() == IrrigationStatus.PLANNED)
				.filter((event) -> event.getStartedAt().isAfter(now.minus(Duration.ofHours(12))))
				.min(Comparator.comparing(IrrigationEvent::getStartedAt))
				.orElse(null);
			IrrigationEvent lastCompleted = fieldEvents.stream()
				.filter((event) -> event.getStatus() == IrrigationStatus.COMPLETED)
				.max(Comparator.comparing(IrrigationEvent::getStartedAt))
				.orElse(null);

			Instant lastObservation = fieldObservations.stream()
				.map(SoilObservation::getObservedAt)
				.max(Comparator.naturalOrder())
				.orElse(null);
			boolean recentObservation = lastObservation != null
					&& lastObservation.isAfter(now.minus(Duration.ofDays(7)));
			Confidence confidence;
			String note;
			if (series.live() && recentObservation) {
				confidence = Confidence.HIGH;
				note = "Live weather and a soil check within the last week.";
			}
			else if (series.live() || recentObservation) {
				confidence = Confidence.MEDIUM;
				note = series.live() ? "Live weather, but no soil check this week. Record one to calibrate the model."
						: "Recent soil check, but weather is estimated from climatology (offline).";
			}
			else {
				confidence = Confidence.LOW;
				note = "Weather is estimated from climatology and there is no recent soil check.";
			}
			if (field.getIrrigationMethod() == null) {
				note += " Irrigation system not configured: drip defaults assumed.";
			}

			states.add(new FieldWaterState(field, crop, (crop != null) ? CropCatalog.parameters(crop.getCropType()) : null,
					result, pump, schedule, energy, cost, confidence, note, nextPlanned, lastCompleted, series.live(),
					series.location().approximate(), today));
		}
		return states;
	}

	/** The most recent planting per field that is still within its season (plus a grace period). */
	private Map<UUID, Crop> activeCrops(Farm farm, LocalDate today) {
		Map<UUID, Crop> active = new HashMap<>();
		for (Crop crop : crops.findByFarm(farm.getId())) { // most recent first
			UUID fieldId = crop.getField().getId();
			if (active.containsKey(fieldId) || crop.getPlantingDate() == null || crop.getPlantingDate().isAfter(today)) {
				continue;
			}
			long age = ChronoUnit.DAYS.between(crop.getPlantingDate(), today);
			if (age <= CropCatalog.parameters(crop.getCropType()).seasonLengthDays() + SEASON_GRACE_DAYS) {
				active.put(fieldId, crop);
			}
			else {
				active.putIfAbsent(fieldId, null);
			}
		}
		active.values().removeIf(java.util.Objects::isNull);
		return active;
	}

	/**
	 * Start time and sessions. Pumps with a power window (solar hours or the grid's load-shedding-free
	 * period) are scheduled inside it; otherwise overhead systems start pre-dawn when wind and
	 * evaporation losses are lowest.
	 */
	static ScheduleView suggestSchedule(Asset pump, IrrigationMethod method, int durationMinutes) {
		LocalTime start;
		int windowMinutes;
		String label;
		if (pump != null && pump.getOperatingWindowStart() != null && pump.getOperatingWindowEnd() != null) {
			start = pump.getOperatingWindowStart();
			long minutes = ChronoUnit.MINUTES.between(start, pump.getOperatingWindowEnd());
			windowMinutes = (int) ((minutes <= 0) ? minutes + 24 * 60 : minutes);
			label = (pump.getPowerSource() == PowerSource.SOLAR) ? "Solar pumping hours" : "Pump power window";
		}
		else if (pump != null && pump.getPowerSource() == PowerSource.SOLAR) {
			start = LocalTime.of(9, 0);
			windowMinutes = 6 * 60;
			label = "Solar pumping hours";
		}
		else if (method == IrrigationMethod.SPRINKLER || method == IrrigationMethod.CENTER_PIVOT
				|| method == IrrigationMethod.MICRO_SPRINKLER) {
			start = LocalTime.of(5, 0);
			windowMinutes = 24 * 60;
			label = "Early morning: least wind drift and evaporation";
		}
		else {
			start = LocalTime.of(6, 0);
			windowMinutes = 24 * 60;
			label = "Morning start";
		}
		int sessions = (durationMinutes > 0) ? (int) Math.ceil((double) durationMinutes / windowMinutes) : 0;
		int sessionMinutes = (sessions > 0) ? (int) Math.ceil((double) durationMinutes / sessions) : 0;
		String note = (sessions > 1)
				? String.format("Split into %d runs of about %s: the job is longer than the %s.", sessions,
						HumanText.duration(sessionMinutes), label.toLowerCase())
				: null;
		return new ScheduleView(start, sessions, sessionMinutes, label, note);
	}

	static Double estimateEnergy(Asset pump, int durationMinutes) {
		if (pump == null || pump.getRatedPowerKw() == null || durationMinutes <= 0) {
			return null;
		}
		return round(pump.getRatedPowerKw().doubleValue() * durationMinutes / 60.0, 1);
	}

	static Double energyCost(Farm farm, Asset pump, Double energyKwh) {
		if (energyKwh == null || pump == null) {
			return null;
		}
		if (pump.getPowerSource() == PowerSource.SOLAR) {
			return 0.0;
		}
		return (farm.getElectricityTariff() != null) ? round(energyKwh * farm.getElectricityTariff().doubleValue(), 2)
				: null;
	}

	private double depthFor(Field field, Double grossDepthMm, Double volumeM3, int durationMinutes) {
		double area = (field.getAreaHa() != null) ? field.getAreaHa().doubleValue() : 1.0;
		if (grossDepthMm != null) {
			return grossDepthMm;
		}
		if (volumeM3 != null) {
			return volumeM3 / (area * 10.0);
		}
		IrrigationMethod method = (field.getIrrigationMethod() != null) ? field.getIrrigationMethod()
				: IrrigationMethod.DEFAULT;
		double rate = (field.getApplicationRateMmH() != null) ? field.getApplicationRateMmH().doubleValue()
				: method.defaultApplicationRateMmH();
		return rate * durationMinutes / 60.0;
	}

	private Asset requirePump(UUID farmId, UUID assetId) {
		Asset asset = assetService.requireInFarm(farmId, assetId);
		if (asset.getType() != AssetType.PUMP) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "The selected asset is not a pump");
		}
		return asset;
	}

	private IrrigationEvent requireEvent(UUID farmId, UUID eventId) {
		return events.findInFarm(eventId, farmId).orElseThrow(() -> ApiException.notFound("Irrigation"));
	}

	private static List<WaterBalanceEngine.IrrigationInput> irrigationInputs(List<IrrigationEvent> fieldEvents,
			ZoneId zone) {
		return fieldEvents.stream()
			.filter((event) -> event.getStatus() == IrrigationStatus.COMPLETED
					|| event.getStatus() == IrrigationStatus.PLANNED)
			.filter((event) -> event.getGrossDepthMm() != null)
			.map((event) -> new WaterBalanceEngine.IrrigationInput(LocalDate.ofInstant(event.getStartedAt(), zone),
					event.getGrossDepthMm().doubleValue(), event.getStatus() == IrrigationStatus.PLANNED))
			.toList();
	}

	/**
	 * Latest soil check per day, with the irrigation that had already reached the field that day (so the
	 * engine does not add it on top of a reading that includes it).
	 */
	private static List<WaterBalanceEngine.ObservationInput> observationInputs(List<SoilObservation> fieldObservations,
			List<IrrigationEvent> fieldEvents, ZoneId zone) {
		Map<LocalDate, SoilObservation> latestPerDay = new LinkedHashMap<>();
		fieldObservations.stream()
			.sorted(Comparator.comparing(SoilObservation::getObservedAt))
			.forEach((observation) -> latestPerDay.put(LocalDate.ofInstant(observation.getObservedAt(), zone),
					observation));
		return latestPerDay.entrySet().stream().map((entry) -> {
			Instant observedAt = entry.getValue().getObservedAt();
			double appliedBefore = fieldEvents.stream()
				.filter((event) -> event.getStatus() == IrrigationStatus.COMPLETED && event.getGrossDepthMm() != null)
				.filter((event) -> LocalDate.ofInstant(event.getStartedAt(), zone).equals(entry.getKey()))
				.filter((event) -> !waterDeliveredAt(event).isAfter(observedAt))
				.mapToDouble((event) -> event.getGrossDepthMm().doubleValue())
				.sum();
			return new WaterBalanceEngine.ObservationInput(entry.getKey(),
					entry.getValue().getDepletionPct().doubleValue(), appliedBefore);
		}).toList();
	}

	/** Water is in the field by the earlier of the confirmation and the end of the run. */
	private static Instant waterDeliveredAt(IrrigationEvent event) {
		Instant runEnd = event.getStartedAt().plus(Duration.ofMinutes(event.getDurationMinutes()));
		return (event.getCompletedAt() != null && event.getCompletedAt().isBefore(runEnd)) ? event.getCompletedAt()
				: runEnd;
	}

	private static WaterBalanceEngine.WeatherInput weatherInput(WeatherDay day) {
		return new WaterBalanceEngine.WeatherInput(day.date(), day.et0Mm(), day.precipitationMm(),
				day.precipitationProbability(), day.forecast());
	}

	// ---- View mapping ----

	private FieldStatusView statusView(FieldWaterState state, Farm farm) {
		Field field = state.field();
		Result result = state.result();
		Recommendation rec = result.recommendation();
		Crop crop = state.crop();
		CropParameters params = state.parameters();
		Integer dap = (crop != null) ? (int) ChronoUnit.DAYS.between(crop.getPlantingDate(), state.today()) : null;
		RecommendationView recommendation = new RecommendationView(rec.decision(), rec.status(), rec.irrigateOn(),
				rec.daysUntilStress(), round(rec.netDepthMm(), 1), round(rec.grossDepthMm(), 1), round(rec.volumeM3(), 1),
				rec.durationMinutes(), state.schedule(), state.energyKwh(), state.energyCost(), farm.getCurrency(),
				round(rec.expectedRainMm(), 1), rec.reasons(), state.confidence(), state.confidenceNote(), RECOMMENDER,
				RECOMMENDER_VERSION);
		Asset pump = state.pump();
		return new FieldStatusView(field.getId(), field.getName(), field.getAreaHa(),
				(crop != null) ? crop.getCropType() : null, (crop != null) ? crop.getVariety() : null,
				(crop != null) ? crop.getPlantingDate() : null, (params != null && dap != null) ? params.phaseAt(dap) : null,
				dap, (params != null) ? params.seasonLengthDays() : null, field.getIrrigationMethod(),
				field.getIrrigationMethod() != null,
				(pump != null) ? new PumpRef(pump.getId(), pump.getName(), pump.getPowerSource()) : null,
				round(result.currentDepletionMm(), 1), round(result.depletionPct(), 1),
				round(result.stressThresholdPct(), 1), round(result.currentTawMm(), 1), recommendation,
				(state.nextPlanned() != null) ? eventView(state.nextPlanned()) : null,
				(state.lastCompleted() != null) ? eventView(state.lastCompleted()) : null,
				(result.season() != null) ? round(result.season().yieldReductionPct(), 1) : null,
				(result.season() != null) ? result.season().stressDays() : null);
	}

	private static SeasonView seasonView(FieldWaterState state) {
		WaterBalanceEngine.SeasonStats season = state.result().season();
		if (season == null) {
			return null;
		}
		return new SeasonView(season.daysAfterPlanting(), season.seasonLengthDays(), season.phase(),
				round(season.kc(), 2), round(season.etcMm(), 1), round(season.etaMm(), 1),
				round(season.effectiveRainMm(), 1), round(season.irrigationGrossMm(), 1), season.stressDays(),
				round(season.relativeEt() * 100, 1), round(season.yieldReductionPct(), 1),
				round(season.attainableYieldTHa(), 2), round(season.expectedYieldTHa(), 2));
	}

	private static SetupView setupView(FieldWaterState state) {
		Field field = state.field();
		Asset pump = state.pump();
		return new SetupView(
				(field.getIrrigationMethod() != null) ? field.getIrrigationMethod() : IrrigationMethod.DEFAULT,
				field.getIrrigationMethod() == null, state.result().applicationRateMmH(),
				field.getApplicationRateMmH() == null, state.result().efficiency(), field.getSystemEfficiency() == null,
				(field.getSoilType() != null) ? field.getSoilType() : SoilCatalog.DEFAULT_SOIL,
				field.getSoilType() == null, SoilCatalog.availableWaterMmPerM(field.getSoilType()),
				(pump != null) ? new PumpRef(pump.getId(), pump.getName(), pump.getPowerSource()) : null);
	}

	private static DayView dayView(Day day) {
		return new DayView(day.date(), day.forecast(), round(day.kc(), 2), round(day.et0Mm(), 1), round(day.etcMm(), 1),
				round(day.etaMm(), 1), round(day.precipitationMm(), 1), round(day.effectiveRainMm(), 1),
				round(day.irrigationGrossMm() - day.plannedIrrigationGrossMm(), 1),
				round(day.plannedIrrigationGrossMm(), 1), round(day.depletionEndMm(), 1),
				round((day.tawMm() > 0) ? 100 * day.depletionEndMm() / day.tawMm() : 0, 1),
				round((day.tawMm() > 0) ? 100 * day.rawMm() / day.tawMm() : 0, 1), round(day.tawMm(), 1),
				round(day.ks(), 2));
	}

	EventView eventView(IrrigationEvent event) {
		boolean overdue = event.getStatus() == IrrigationStatus.PLANNED
				&& event.getStartedAt().isBefore(clock.instant().minus(Duration.ofHours(2)));
		return new EventView(event.getId(), event.getField().getId(), event.getField().getName(), event.getStatus(),
				event.getSource(), event.getStartedAt(), event.getDurationMinutes(), event.getGrossDepthMm(),
				round(event.effectiveLitres() / 1000.0, 1), event.getPumpAssetId(), event.getEnergyKwh(),
				event.getNotes(), event.getCompletedAt(), overdue);
	}

	private static Double toDouble(BigDecimal value) {
		return (value != null) ? value.doubleValue() : null;
	}

	static double round(double value, int digits) {
		double factor = Math.pow(10, digits);
		return Math.round(value * factor) / factor;
	}

}
