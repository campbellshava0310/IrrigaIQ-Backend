package com.irrigaiq.ml;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.irrigaiq.agronomy.GrowthPhase;
import com.irrigaiq.agronomy.SoilMoistureScale;
import com.irrigaiq.agronomy.WaterBalanceEngine.SeasonStats;
import com.irrigaiq.agronomy.WaterBalanceEngine.WaterStatus;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.irrigation.IrrigationService;
import com.irrigaiq.irrigation.IrrigationService.FieldWaterState;
import com.irrigaiq.ml.MlApi.FieldFeatures;
import com.irrigaiq.ml.MlApi.IrrigationPrediction;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.telemetry.DeviceService;
import com.irrigaiq.telemetry.DeviceStatus;
import com.irrigaiq.telemetry.Reading;
import com.irrigaiq.telemetry.ReadingStore;
import com.irrigaiq.telemetry.Sensor;
import com.irrigaiq.telemetry.SensorType;
import com.irrigaiq.weather.WeatherDay;
import com.irrigaiq.weather.WeatherService;
import com.irrigaiq.weather.WeatherService.WeatherSeries;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The irrigation-need model's view of each field, next to the FAO-56 water balance. The model
 * answers "does this field need water, how sure are we, and why"; volumes, run times and timing
 * stay with the water balance, which is physically grounded. Where the two disagree, the
 * disagreement is surfaced rather than hidden.
 */
@Service
public class IrrigationModelService {

	/** A probe reading this recent is used directly; older ones only through the water balance. */
	static final Duration PROBE_FRESH = Duration.ofHours(6);

	/**
	 * The model's rainfall input is annual-scale, and in its training data rainfall below about 300 mm
	 * marks dry conditions. Recent rain scaled to a year keeps that meaning for today: a dry spell
	 * reads as dry even on a farm whose yearly total is high (most of it fell months ago).
	 */
	static final int RAIN_WINDOW_DAYS = 90;

	/** Open-Meteo reports no wind for some days; the dataset's median is used and flagged. */
	static final double DEFAULT_WIND_KMH = 10.5;

	private static final Set<WaterStatus> NEEDS_WATER = EnumSet.of(WaterStatus.IRRIGATE_NOW, WaterStatus.STRESSED);

	private final FarmService farms;

	private final IrrigationService irrigation;

	private final WeatherService weather;

	private final DeviceService devices;

	private final ReadingStore readings;

	private final MlClient ml;

	private final Clock clock;

	public IrrigationModelService(FarmService farms, IrrigationService irrigation, WeatherService weather,
			DeviceService devices, ReadingStore readings, MlClient ml, Clock clock) {
		this.farms = farms;
		this.irrigation = irrigation;
		this.weather = weather;
		this.devices = devices;
		this.readings = readings;
		this.ml = ml;
		this.clock = clock;
	}

	/**
	 * What the model was given. {@code soilMoisturePct} is on the model's scale; the probe's own
	 * reading is reported separately when one was used.
	 */
	public record Inputs(double soilMoisturePct, String soilMoistureSource, String probeName, Double probeReadingPct,
			Instant probeReadAt, double depletionPct, double temperatureC, double windSpeedKmh, boolean windAssumed,
			double rainfallLast90DaysMm, double rainfallMmAnnualised, GrowthPhase growthPhase, boolean mulched,
			boolean weatherLive) {
	}

	/** @param consistent whether the model's need level is compatible with the water balance's status */
	public record Agreement(boolean consistent, WaterStatus waterBalanceStatus, String note) {
	}

	/** @param status {@code OK}, {@code NO_CROP} (nothing planted) or {@code UNAVAILABLE} (model offline) */
	public record FieldAssessment(UUID fieldId, String fieldName, String status, IrrigationPrediction prediction,
			Inputs inputs, Agreement agreement) {
	}

	public record IrrigationModelView(boolean available, Instant assessedAt, String modelVersion,
			List<FieldAssessment> fields) {
	}

	@Transactional(readOnly = true)
	public IrrigationModelView assess(UUID farmId, AuthenticatedUser user) {
		return assess(farms.requireAccessible(farmId, user));
	}

	@Transactional(readOnly = true)
	public IrrigationModelView assess(Farm farm) {
		List<FieldWaterState> states = irrigation.evaluate(farm);
		LocalDate today = weather.today(farm);
		WeatherSeries series = weather.series(farm, today.minusDays(RAIN_WINDOW_DAYS), today);
		WeatherDay todayWeather = series.on(today);
		double rain = series.days()
			.stream()
			.filter((day) -> day.date().isBefore(today) && day.precipitationMm() != null)
			.mapToDouble(WeatherDay::precipitationMm)
			.sum();
		double temperature = (todayWeather != null && todayWeather.tempMaxC() != null) ? todayWeather.tempMaxC() : 25;
		boolean windAssumed = todayWeather == null || todayWeather.windMaxKmh() == null;
		double wind = windAssumed ? DEFAULT_WIND_KMH : todayWeather.windMaxKmh();

		Map<UUID, Sensor> probes = devices.devices(farm, SensorType.SOIL_MOISTURE)
			.stream()
			.filter((probe) -> probe.getStatus() == DeviceStatus.ACTIVE && probe.getFieldId() != null)
			.collect(Collectors.toMap(Sensor::getFieldId, Function.identity(), (a, b) -> a));
		Instant freshSince = clock.instant().minus(PROBE_FRESH);

		List<FieldFeatures> features = new ArrayList<>();
		Map<UUID, Inputs> inputs = new HashMap<>();
		for (FieldWaterState state : states) {
			SeasonStats season = state.result().season();
			if (state.crop() == null || season == null) {
				continue;
			}
			Sensor probe = probes.get(state.field().getId());
			Reading latest = (probe != null) ? readings.latest(probe.getId())
				.filter((r) -> r.soilMoisturePct() != null && r.recordedAt().isAfter(freshSince))
				.orElse(null) : null;
			double depletion = (latest != null) ? DeviceService.depletionPct(probe, latest.soilMoisturePct())
					: state.result().depletionPct();
			Inputs in = new Inputs(round(SoilMoistureScale.defaultScaleReading(depletion)),
					(latest != null) ? "PROBE" : "WATER_BALANCE", (latest != null) ? probe.displayName() : null,
					(latest != null) ? latest.soilMoisturePct() : null, (latest != null) ? latest.recordedAt() : null,
					round(depletion), temperature, wind, windAssumed, round(rain), round(rain * 365 / RAIN_WINDOW_DAYS),
					season.phase(), state.field().isMulched(), series.live());
			inputs.put(state.field().getId(), in);
			features.add(new FieldFeatures(state.field().getId().toString(), in.soilMoisturePct(), in.temperatureC(),
					in.windSpeedKmh(), in.rainfallMmAnnualised(), in.growthPhase().name(), in.mulched()));
		}

		Optional<List<IrrigationPrediction>> predictions = ml.predictIrrigation(features);
		Map<String, IrrigationPrediction> byField = predictions.orElse(List.of())
			.stream()
			.collect(Collectors.toMap(IrrigationPrediction::fieldId, Function.identity()));

		List<FieldAssessment> out = new ArrayList<>(states.size());
		for (FieldWaterState state : states) {
			UUID id = state.field().getId();
			Inputs in = inputs.get(id);
			if (in == null) {
				out.add(new FieldAssessment(id, state.field().getName(), "NO_CROP", null, null, null));
				continue;
			}
			IrrigationPrediction prediction = byField.get(id.toString());
			if (prediction == null) {
				out.add(new FieldAssessment(id, state.field().getName(), "UNAVAILABLE", null, in, null));
				continue;
			}
			out.add(new FieldAssessment(id, state.field().getName(), "OK", prediction, in,
					agreement(prediction, state.recommendation().status())));
		}
		String version = byField.values().stream().map(IrrigationPrediction::modelVersion).findFirst().orElse(null);
		return new IrrigationModelView(predictions.isPresent(), clock.instant(), version, out);
	}

	/**
	 * The model's three need levels against the water balance: High pairs with "irrigate now" or
	 * stress, Low with adequate water, and Medium (drying, not yet stressed) with anything short of stress.
	 */
	static Agreement agreement(IrrigationPrediction prediction, WaterStatus status) {
		boolean balanceNeeds = NEEDS_WATER.contains(status);
		boolean consistent = switch (prediction.need()) {
			case "High" -> balanceNeeds || status == WaterStatus.IRRIGATE_SOON;
			case "Low" -> !balanceNeeds;
			default -> status != WaterStatus.STRESSED;
		};
		String note;
		if (consistent) {
			note = balanceNeeds ? "The model and the water balance agree the field needs water."
					: "The model and the water balance agree on the field's water status.";
		}
		else if (balanceNeeds) {
			note = "The water balance says irrigate but the model rates the need " + prediction.need().toLowerCase()
					+ ". Check soil moisture before deciding.";
		}
		else {
			note = "The model rates the need high but the water balance shows enough water. Check soil moisture: "
					+ "the balance may be missing a recent change.";
		}
		return new Agreement(consistent, status, note);
	}

	private static double round(double value) {
		return Math.round(value * 10) / 10.0;
	}

}
