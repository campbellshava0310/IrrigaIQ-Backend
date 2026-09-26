package com.irrigaiq.intelligence;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.irrigaiq.alerts.AlertService;
import com.irrigaiq.alerts.AlertService.AlertSummary;
import com.irrigaiq.assets.AssetService;
import com.irrigaiq.assets.AssetViews.AssetHealthSnapshot;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.irrigation.IrrigationService;
import com.irrigaiq.irrigation.IrrigationService.FieldWaterState;
import com.irrigaiq.maintenance.WorkOrderService;
import com.irrigaiq.maintenance.WorkOrderService.WorkOrderView;
import com.irrigaiq.water.WaterService;
import com.irrigaiq.water.WaterService.WaterSummary;
import com.irrigaiq.weather.WeatherService;
import com.irrigaiq.weather.WeatherService.WeatherSeries;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the dashboard and the briefing need, gathered once per request from the verified
 * platform data (no second source of truth).
 */
record FarmSnapshot(Farm farm, LocalDate today, List<FieldWaterState> fields, List<AssetHealthSnapshot> assets,
		List<WorkOrderView> workOrders, WaterSummary week, WaterSummary month, WeatherSeries weather,
		AlertSummary alerts) {

	@Component
	static class Assembler {

		private final IrrigationService irrigation;

		private final AssetService assets;

		private final WorkOrderService workOrders;

		private final WaterService water;

		private final WeatherService weather;

		private final AlertService alerts;

		private final Clock clock;

		Assembler(IrrigationService irrigation, AssetService assets, WorkOrderService workOrders, WaterService water,
				WeatherService weather, AlertService alerts, Clock clock) {
			this.irrigation = irrigation;
			this.assets = assets;
			this.workOrders = workOrders;
			this.water = water;
			this.weather = weather;
			this.alerts = alerts;
			this.clock = clock;
		}

		@Transactional(readOnly = true)
		FarmSnapshot assemble(Farm farm) {
			LocalDate today = LocalDate.now(clock.withZone(farm.zone()));
			return new FarmSnapshot(farm, today, irrigation.evaluate(farm), assets.assess(farm),
					workOrders.forFarm(farm), water.summarize(farm, today.minusDays(6), today),
					water.summarize(farm, today.minusDays(29), today),
					weather.series(farm, today, today.plusDays(6)), alerts.summary(farm));
		}

	}

}
