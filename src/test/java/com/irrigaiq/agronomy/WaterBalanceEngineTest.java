package com.irrigaiq.agronomy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.irrigaiq.agronomy.WaterBalanceEngine.CropInput;
import com.irrigaiq.agronomy.WaterBalanceEngine.Decision;
import com.irrigaiq.agronomy.WaterBalanceEngine.Input;
import com.irrigaiq.agronomy.WaterBalanceEngine.IrrigationInput;
import com.irrigaiq.agronomy.WaterBalanceEngine.ObservationInput;
import com.irrigaiq.agronomy.WaterBalanceEngine.Result;
import com.irrigaiq.agronomy.WaterBalanceEngine.WaterStatus;
import com.irrigaiq.agronomy.WaterBalanceEngine.WeatherInput;
import com.irrigaiq.farm.CropType;
import com.irrigaiq.farm.IrrigationMethod;
import com.irrigaiq.farm.SoilType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WaterBalanceEngineTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

	@Test
	void kcFollowsTheFao56Curve() {
		CropParameters tomato = CropCatalog.parameters(CropType.TOMATO); // 30/40/40/25, Kc 0.60/1.15/0.70
		assertThat(tomato.kcAt(0)).isEqualTo(0.60);
		assertThat(tomato.kcAt(50)).isCloseTo(0.60 + 0.55 * 20 / 40, within(1e-9));
		assertThat(tomato.kcAt(80)).isEqualTo(1.15);
		assertThat(tomato.kcAt(134)).isCloseTo(0.70, within(0.05));
		assertThat(tomato.kcAt(135)).isEqualTo(CropParameters.BARE_SOIL_KC);
		assertThat(tomato.phaseAt(135)).isEqualTo(GrowthPhase.HARVEST);
		assertThat(tomato.rootDepthAt(0)).isEqualTo(0.25);
		assertThat(tomato.rootDepthAt(70)).isEqualTo(0.80);
	}

	@Test
	void depletionAccumulatesCropWaterUseUntilIrrigationIsNeeded() {
		Result result = WaterBalanceEngine.run(input(80, dry(80, 0), List.of(), List.of()));

		assertThat(result.currentDepletionMm()).isGreaterThanOrEqualTo(result.currentRawMm());
		assertThat(result.recommendation().decision()).isEqualTo(Decision.IRRIGATE_NOW);
		assertThat(result.recommendation().status()).isIn(WaterStatus.IRRIGATE_NOW, WaterStatus.STRESSED);
		// Gross depth covers the deficit through the 90 % drip efficiency; 1 mm on 1 ha = 10 m³.
		assertThat(result.recommendation().grossDepthMm())
			.isCloseTo(result.recommendation().netDepthMm() / 0.90, within(0.01));
		assertThat(result.recommendation().volumeM3())
			.isCloseTo(result.recommendation().grossDepthMm() * 2.0 * 10, within(0.01));
		assertThat(result.recommendation().durationMinutes()).isPositive();
	}

	@Test
	void recentIrrigationKeepsTheFieldOnTrack() {
		List<IrrigationInput> irrigation = List.of(new IrrigationInput(TODAY.minusDays(1), 120, false));
		Result result = WaterBalanceEngine.run(input(80, dry(80, 0), irrigation, List.of()));

		assertThat(result.recommendation().decision()).isIn(Decision.ON_TRACK, Decision.IRRIGATE_SOON);
		assertThat(result.depletionPct()).isLessThan(result.stressThresholdPct());
	}

	@Test
	void forecastRainCoveringTheDeficitHoldsIrrigation() {
		// Field observed just below the threshold; a certain 40 mm storm arrives tomorrow.
		List<WeatherInput> weather = dry(80, 7);
		weather.set(weather.size() - 7, new WeatherInput(TODAY.plusDays(1), 5.0, 40.0, 100, true));
		List<ObservationInput> check = List.of(new ObservationInput(TODAY, 38));
		Result result = WaterBalanceEngine.run(input(80, weather, List.of(), check));

		assertThat(result.recommendation().decision()).isEqualTo(Decision.HOLD_FOR_RAIN);
		assertThat(result.recommendation().expectedRainMm()).isGreaterThan(20);
	}

	@Test
	void anObservationResetsTheModelledDepletion() {
		Result result = WaterBalanceEngine.run(input(80, dry(80, 0), List.of(), List.of(new ObservationInput(TODAY, 10))));

		assertThat(result.depletionPct()).isCloseTo(10, within(0.5));
		assertThat(result.recommendation().decision()).isNotEqualTo(Decision.IRRIGATE_NOW);
	}

	@Test
	void aSoilCheckAfterSameDayIrrigationIsNotRefilledTwice() {
		List<IrrigationInput> irrigated = List.of(new IrrigationInput(TODAY, 120, false));
		Result checkedAfter = WaterBalanceEngine.run(input(80, dry(80, 0), irrigated,
				List.of(new ObservationInput(TODAY, 15, 120))));
		Result checkedBefore = WaterBalanceEngine.run(input(80, dry(80, 0), irrigated,
				List.of(new ObservationInput(TODAY, 60))));

		assertThat(checkedAfter.depletionPct()).isCloseTo(15, within(0.5));
		assertThat(checkedBefore.depletionPct()).isZero(); // the irrigation came after the reading
	}

	@Test
	void overheadSystemsRefillLargeDeficitsOverSeveralPasses() {
		Input dryPivot = new Input(TODAY, 30.0, SoilType.CLAY_LOAM, new CropInput(CropType.MAIZE, TODAY.minusDays(60), 11.0),
				IrrigationMethod.CENTER_PIVOT, 1.0, 0.85, dry(60, 0), List.of(), List.of());
		Result result = WaterBalanceEngine.run(dryPivot);

		assertThat(result.currentDepletionMm()).isGreaterThan(IrrigationMethod.CENTER_PIVOT.maxNetApplicationMm());
		assertThat(result.recommendation().netDepthMm()).isEqualTo(IrrigationMethod.CENTER_PIVOT.maxNetApplicationMm());
		assertThat(result.recommendation().reasons()).anyMatch((reason) -> reason.contains("per pass"));
	}

	@Test
	void plannedIrrigationWithinTwoDaysMarksTheFieldScheduled() {
		List<IrrigationInput> planned = List.of(new IrrigationInput(TODAY, 40, true));
		Result result = WaterBalanceEngine.run(input(80, dry(80, 7), planned, List.of()));

		assertThat(result.recommendation().decision()).isEqualTo(Decision.SCHEDULED);
	}

	@Test
	void waterStressReducesExpectedYield() {
		Result unirrigated = WaterBalanceEngine.run(input(100, dry(100, 0), List.of(), List.of()));

		assertThat(unirrigated.season().stressDays()).isPositive();
		assertThat(unirrigated.season().yieldReductionPct()).isGreaterThan(0);
		assertThat(unirrigated.season().expectedYieldTHa()).isLessThan(unirrigated.season().attainableYieldTHa());
	}

	@Test
	void noCropMeansNoRecommendation() {
		Result result = WaterBalanceEngine.run(new Input(TODAY, 2.0, SoilType.LOAM, null, null, null, null,
				dry(10, 0), List.of(), List.of()));

		assertThat(result.recommendation().decision()).isEqualTo(Decision.NOT_PLANTED);
		assertThat(result.days()).isEmpty();
	}

	@Test
	void effectiveRainIgnoresInterceptionAndRunoff() {
		assertThat(WaterBalanceEngine.effectiveRain(1.5)).isZero();
		assertThat(WaterBalanceEngine.effectiveRain(12)).isCloseTo(8.0, within(1e-9));
	}

	private static Input input(int daysSincePlanting, List<WeatherInput> weather, List<IrrigationInput> irrigation,
			List<ObservationInput> observations) {
		return new Input(TODAY, 2.0, SoilType.SANDY_LOAM,
				new CropInput(CropType.TOMATO, TODAY.minusDays(daysSincePlanting), 60.0), IrrigationMethod.DRIP, 3.0,
				0.90, weather, irrigation, observations);
	}

	/** Hot dry weather (ET0 6 mm/day, no rain) for the history and optional forecast days. */
	private static List<WeatherInput> dry(int historyDays, int forecastDays) {
		List<WeatherInput> days = new ArrayList<>();
		for (int i = historyDays; i >= -forecastDays; i--) {
			LocalDate date = TODAY.minusDays(i);
			days.add(new WeatherInput(date, 6.0, 0.0, 0, i < 0));
		}
		return days;
	}

}
