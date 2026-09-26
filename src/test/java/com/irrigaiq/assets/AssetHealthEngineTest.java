package com.irrigaiq.assets;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.irrigaiq.assets.AssetHealthEngine.CheckInput;
import com.irrigaiq.assets.AssetHealthEngine.Factor;
import com.irrigaiq.assets.AssetHealthEngine.Health;
import com.irrigaiq.assets.AssetHealthEngine.Input;

import static org.assertj.core.api.Assertions.assertThat;

class AssetHealthEngineTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

	@Test
	void wellMaintainedPumpIsLowRisk() {
		Health health = AssetHealthEngine.assess(pump(AssetStatus.OPERATIONAL, TODAY.minusDays(30), 100, 0,
				List.of(new CheckInput(TODAY.minusDays(5), OverallCondition.GOOD, VibrationLevel.NORMAL, false, null,
						13.3, 60.0, null))));

		assertThat(health.risk()).isEqualTo(RiskLevel.LOW);
		assertThat(health.score()).isGreaterThanOrEqualTo(80);
		assertThat(health.service().overdue()).isFalse();
		assertThat(health.energyDriftPct()).isCloseTo(0.8, org.assertj.core.api.Assertions.within(0.5));
	}

	@Test
	void energyDriftVibrationAndOverdueServiceMakeAPumpCritical() {
		Health health = AssetHealthEngine.assess(pump(AssetStatus.OPERATIONAL, TODAY.minusDays(205), 1400, 0,
				List.of(new CheckInput(TODAY.minusDays(2), OverallCondition.FAIR, VibrationLevel.ELEVATED, false,
						null, 14.8, 54.6, null))));

		assertThat(health.risk()).isIn(RiskLevel.HIGH, RiskLevel.CRITICAL);
		assertThat(health.energyDriftPct()).isGreaterThan(20);
		assertThat(health.factors()).extracting(Factor::code)
			.contains("ENERGY_DRIFT", "SERVICE_OVERDUE", "VIBRATION", "FAIR_CONDITION");
		assertThat(health.factors().get(0).impact()).isNegative(); // worst factor first
		assertThat(health.recommendedAction()).isNotBlank();
	}

	@Test
	void runHourServicesAreForecastFromRecentUsage() {
		// 1400 of 1500 run-hours used, running 10 h/day: service due in 10 days.
		Health health = AssetHealthEngine.assess(new Input(TODAY, AssetType.PUMP, AssetStatus.OPERATIONAL,
				LocalDate.of(2020, 1, 1), 15, null, 1500, 5400, 4000, TODAY.minusDays(60), null, null, null,
				List.of(), 0, 10));

		assertThat(health.service().basis()).isEqualTo("RUN_HOURS");
		assertThat(health.service().dueOn()).isEqualTo(TODAY.plusDays(10));
		assertThat(health.factors()).extracting(Factor::code).contains("SERVICE_DUE_SOON");
	}

	@Test
	void cloggedFilterIsFlagged() {
		Health health = AssetHealthEngine.assess(new Input(TODAY, AssetType.FILTER, AssetStatus.OPERATIONAL,
				LocalDate.of(2021, 1, 1), 10, 30, null, 0, 0, TODAY.minusDays(10), null, null, null,
				List.of(new CheckInput(TODAY.minusDays(1), OverallCondition.GOOD, null, false, 0.64, null, null, null)),
				0, 0));

		assertThat(health.factors()).extracting(Factor::code).contains("FILTER_CLOGGING");
	}

	@Test
	void fallingBoreholeLevelIsFlagged() {
		Health health = AssetHealthEngine.assess(new Input(TODAY, AssetType.BOREHOLE, AssetStatus.OPERATIONAL,
				LocalDate.of(2019, 1, 1), 40, 365, null, 0, 0, TODAY.minusDays(100), null, null, null,
				List.of(new CheckInput(TODAY.minusDays(80), OverallCondition.GOOD, null, false, null, null, null, 42.1),
						new CheckInput(TODAY.minusDays(3), OverallCondition.GOOD, null, false, null, null, null, 44.7)),
				0, 0));

		assertThat(health.factors()).extracting(Factor::code).contains("WATER_LEVEL_FALLING");
	}

	@Test
	void anAssetMarkedDownIsCritical() {
		Health health = AssetHealthEngine.assess(pump(AssetStatus.DOWN, TODAY.minusDays(10), 0, 0, List.of()));

		assertThat(health.score()).isLessThanOrEqualTo(20);
		assertThat(health.risk()).isEqualTo(RiskLevel.CRITICAL);
	}

	private static Input pump(AssetStatus status, LocalDate lastService, double hoursSinceService, int openRepairs,
			List<CheckInput> checks) {
		return new Input(TODAY, AssetType.PUMP, status, LocalDate.of(2016, 9, 20), 12, 180, 1500,
				9790 + hoursSinceService, 9790, lastService, 0.22, 15.0, 60.0, checks, openRepairs, 5);
	}

}
