package com.irrigaiq.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import com.irrigaiq.agronomy.WaterBalanceEngine;
import com.irrigaiq.alerts.AlertEvaluator;
import com.irrigaiq.assets.Asset;
import com.irrigaiq.assets.AssetConditionCheck;
import com.irrigaiq.assets.AssetConditionCheck.CheckDetails;
import com.irrigaiq.assets.AssetDetails;
import com.irrigaiq.assets.AssetRepository;
import com.irrigaiq.assets.AssetStatus;
import com.irrigaiq.assets.AssetType;
import com.irrigaiq.assets.ConditionCheckRepository;
import com.irrigaiq.assets.Criticality;
import com.irrigaiq.assets.OverallCondition;
import com.irrigaiq.assets.PowerSource;
import com.irrigaiq.assets.VibrationLevel;
import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.farm.Crop;
import com.irrigaiq.farm.CropRepository;
import com.irrigaiq.farm.CropType;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmDetails;
import com.irrigaiq.farm.FarmRepository;
import com.irrigaiq.farm.Field;
import com.irrigaiq.farm.FieldRepository;
import com.irrigaiq.farm.GrowthStage;
import com.irrigaiq.farm.IrrigationMethod;
import com.irrigaiq.farm.SoilType;
import com.irrigaiq.identity.EmailAddresses;
import com.irrigaiq.identity.User;
import com.irrigaiq.identity.UserRepository;
import com.irrigaiq.irrigation.IrrigationEvent;
import com.irrigaiq.irrigation.IrrigationEventRepository;
import com.irrigaiq.irrigation.IrrigationSource;
import com.irrigaiq.irrigation.IrrigationStatus;
import com.irrigaiq.irrigation.ObservationMethod;
import com.irrigaiq.irrigation.SoilObservation;
import com.irrigaiq.irrigation.SoilObservationRepository;
import com.irrigaiq.maintenance.WorkOrder;
import com.irrigaiq.maintenance.WorkOrder.WorkOrderDetails;
import com.irrigaiq.maintenance.WorkOrderKind;
import com.irrigaiq.maintenance.WorkOrderPriority;
import com.irrigaiq.maintenance.WorkOrderRepository;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.weather.WeatherDay;
import com.irrigaiq.weather.WeatherService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the summit demo farm: a realistic Mashonaland estate whose history is produced by the
 * same water-balance engine the product uses, with the MVP scenarios built in. Deterministic
 * apart from live weather, and clearly labelled as demo data.
 */
@Service
public class DemoFarmService {

	static final long SEED = 20_260_925L;

	private final UserRepository users;

	private final FarmRepository farms;

	private final FieldRepository fields;

	private final CropRepository crops;

	private final AssetRepository assets;

	private final ConditionCheckRepository checks;

	private final IrrigationEventRepository irrigations;

	private final SoilObservationRepository observations;

	private final WorkOrderRepository workOrders;

	private final WeatherService weather;

	private final AlertEvaluator alerts;

	private final AuditService audit;

	private final Clock clock;

	public DemoFarmService(UserRepository users, FarmRepository farms, FieldRepository fields, CropRepository crops,
			AssetRepository assets, ConditionCheckRepository checks, IrrigationEventRepository irrigations,
			SoilObservationRepository observations, WorkOrderRepository workOrders, WeatherService weather,
			AlertEvaluator alerts, AuditService audit, Clock clock) {
		this.users = users;
		this.farms = farms;
		this.fields = fields;
		this.crops = crops;
		this.assets = assets;
		this.checks = checks;
		this.irrigations = irrigations;
		this.observations = observations;
		this.workOrders = workOrders;
		this.weather = weather;
		this.alerts = alerts;
		this.audit = audit;
		this.clock = clock;
	}

	public record DemoFarmResult(UUID farmId, String farmName, String ownerEmail, int fields, int assets,
			int irrigations, int conditionChecks, int workOrders, int openAlerts) {
	}

	private record FieldPlan(String name, double areaHa, SoilType soil, IrrigationMethod method, double rateMmH,
			double efficiency, CropType crop, String variety, int daysSincePlanting, double yieldTHa, double lat,
			double lon) {
	}

	@Transactional
	public DemoFarmResult create(AuthenticatedUser actor, String ownerEmail) {
		User owner = (ownerEmail != null && !ownerEmail.isBlank())
				? users.findByEmail(EmailAddresses.normalize(ownerEmail)).orElseThrow(() -> ApiException.notFound("User"))
				: users.findById(actor.id()).orElseThrow(() -> ApiException.notFound("User"));
		SplittableRandom random = new SplittableRandom(SEED);
		Instant now = clock.instant();

		Farm farm = farms.save(new Farm(owner, new FarmDetails("Mazowe Valley Estate (demo)",
				"Mazowe, Mashonaland Central, Zimbabwe", new BigDecimal("64.00"), new BigDecimal("-17.505000"),
				new BigDecimal("30.975000"), "Africa/Harare", "USD", new BigDecimal("0.1200"), 3,
				new BigDecimal("25.00"))));
		ZoneId zone = farm.zone();
		LocalDate today = LocalDate.now(clock.withZone(zone));

		// ---- Infrastructure network: sources -> pumps -> treatment -> distribution -> application ----
		Asset borehole = asset(farm, AssetType.BOREHOLE, "BH-1 Borehole", null, null, "Drilled 2019", null,
				LocalDate.of(2019, 6, 14), Criticality.HIGH, null, null, null, null, null, new BigDecimal("90000"),
				null, null, 365, null, 40, today.minusDays(150), null, 0, "Static water level logged monthly.");
		Asset solarArray = asset(farm, AssetType.SOLAR_ARRAY, "SA-1 Solar array 15 kWp", null, null, "Canadian Solar",
				"HiKu 550 W x 28", LocalDate.of(2023, 3, 2), Criticality.HIGH, PowerSource.SOLAR, new BigDecimal("15.00"),
				null, null, null, null, null, null, 30, null, 25, today.minusDays(38), null, 0,
				"Panels cleaned monthly in the dry season: dust costs up to 20% output.");
		Asset solarPump = asset(farm, AssetType.PUMP, "P-1 Solar borehole pump", borehole.getId(), null, "Grundfos",
				"SP 17-8", LocalDate.of(2023, 3, 2), Criticality.HIGH, PowerSource.SOLAR, new BigDecimal("7.50"),
				new BigDecimal("18.00"), new BigDecimal("90.00"), null, null, LocalTime.of(9, 0), LocalTime.of(15, 30),
				365, 2000, 15, today.minusDays(120), new BigDecimal("0.4200"), 4410.0,
				"Submersible, 9:00-15:30 on solar.");
		Asset dam = asset(farm, AssetType.RESERVOIR, "DAM-1 Farm dam", null, null, null, null,
				LocalDate.of(2004, 1, 1), Criticality.CRITICAL, null, null, null, null, new BigDecimal("180000"),
				new BigDecimal("250000"), null, null, null, null, null, null, null, 0, "Licensed abstraction.");
		Asset gridPump = asset(farm, AssetType.PUMP, "P-2 Dam pump (electric)", dam.getId(), null, "KSB",
				"Etanorm 050-200", LocalDate.of(2016, 9, 20), Criticality.HIGH, PowerSource.GRID, new BigDecimal("15.00"),
				new BigDecimal("60.00"), new BigDecimal("45.00"), null, null, LocalTime.of(22, 0), LocalTime.of(5, 0),
				180, 1500, 12, today.minusDays(205), new BigDecimal("0.2200"), 9790.0,
				"Runs in the night window that is free of load-shedding.");
		Asset filter = asset(farm, AssetType.FILTER, "F-1 Disc filter bank", gridPump.getId(), null, "Netafim",
				"Apollo Disc 4x3\"", LocalDate.of(2021, 8, 1), Criticality.MEDIUM, null, null, new BigDecimal("60.00"),
				null, null, null, null, null, 30, null, 10, today.minusDays(44), null, 0, "120 micron discs.");
		Asset mainline = asset(farm, AssetType.MAINLINE, "ML-1 Mainline 110 mm PVC", filter.getId(), null, null,
				"Class 6 PVC", LocalDate.of(2016, 9, 20), Criticality.MEDIUM, null, null, null, null, null, null, null,
				null, 365, null, 30, today.minusDays(300), null, 0, null);
		Asset pivotPump = asset(farm, AssetType.PUMP, "P-3 Pivot booster pump", dam.getId(), null, "Grundfos",
				"NB 100-250", LocalDate.of(2020, 10, 5), Criticality.CRITICAL, PowerSource.GRID,
				new BigDecimal("45.00"), new BigDecimal("150.00"), new BigDecimal("70.00"), null, null,
				LocalTime.of(22, 0), LocalTime.of(6, 0), 365, 3000, 15, today.minusDays(120), new BigDecimal("0.3000"),
				2050.0, null);
		// Fields and assets reference each other by plain id columns, which Hibernate cannot order
		// inserts by: write each layer before the next one points at it.
		assets.flush();

		List<FieldPlan> plans = List.of(
				new FieldPlan("Block A · Tomatoes", 2.5, SoilType.SANDY_LOAM, IrrigationMethod.DRIP, 3.0, 0.90,
						CropType.TOMATO, "Star 9037", 68, 60, -17.5031, 30.9731),
				new FieldPlan("Block B · Tomatoes", 2.0, SoilType.SANDY_LOAM, IrrigationMethod.DRIP, 3.0, 0.90,
						CropType.TOMATO, "Roma VF", 52, 55, -17.5044, 30.9752),
				new FieldPlan("Pivot 1 · Maize", 30.0, SoilType.CLAY_LOAM, IrrigationMethod.CENTER_PIVOT, 0.6, 0.85,
						CropType.MAIZE, "SC 719", 42, 11, -17.5078, 30.9790),
				new FieldPlan("Block C · Potatoes", 4.0, SoilType.LOAM, IrrigationMethod.SPRINKLER, 6.0, 0.75,
						CropType.POTATO, "BP1", 22, 35, -17.5019, 30.9772));
		Asset[] fieldPumps = { solarPump, gridPump, pivotPump, gridPump };

		List<Field> createdFields = new ArrayList<>();
		for (int i = 0; i < plans.size(); i++) {
			FieldPlan plan = plans.get(i);
			Field field = fields.save(new Field(farm, plan.name(), BigDecimal.valueOf(plan.areaHa()), plan.soil(),
					BigDecimal.valueOf(plan.lat()), BigDecimal.valueOf(plan.lon())));
			field.configureIrrigation(plan.method(), BigDecimal.valueOf(plan.rateMmH()),
					BigDecimal.valueOf(plan.efficiency()), fieldPumps[i].getId());
			crops.save(new Crop(field, plan.crop(), plan.variety(), today.minusDays(plan.daysSincePlanting()),
					stageFor(plan.crop(), plan.daysSincePlanting()), BigDecimal.valueOf(plan.yieldTHa())));
			createdFields.add(field);
		}
		fields.flush();

		Asset dripA = asset(farm, AssetType.DRIP_SYSTEM, "Drip system · Block A", solarPump.getId(),
				createdFields.get(0).getId(), "Netafim", "Streamline X 16 mm", LocalDate.of(2023, 3, 10),
				Criticality.MEDIUM, null, null, null, null, null, null, null, null, 90, null, 8, today.minusDays(60),
				null, 0, "Laterals flushed quarterly; acid treatment yearly.");
		Asset dripB = asset(farm, AssetType.DRIP_SYSTEM, "Drip system · Block B", mainline.getId(),
				createdFields.get(1).getId(), "Netafim", "Streamline X 16 mm", LocalDate.of(2021, 8, 5),
				Criticality.MEDIUM, null, null, null, null, null, null, null, null, 90, null, 8, today.minusDays(100),
				null, 0, null);
		Asset pivot = asset(farm, AssetType.CENTER_PIVOT, "CP-1 Centre pivot 30 ha", pivotPump.getId(),
				createdFields.get(2).getId(), "Valley", "8000 series, 5 towers", LocalDate.of(2020, 10, 5),
				Criticality.CRITICAL, null, null, new BigDecimal("150.00"), null, null, null, null, null, null, 1000,
				25, today.minusDays(55), null, 1180.0, "Gearbox oil and tyre pressure at every service.");
		pivot.addRunHours(920);
		gridPump.addRunHours(1390);
		solarPump.addRunHours(640);
		pivotPump.addRunHours(700);

		int checkCount = 0;
		// P-2: energy per m³ creeping up (+23 %) and vibration rising: the guide's "Pump 02" scenario.
		double[][] pumpTrend = { { 90, 13.3, 59.5 }, { 76, 13.4, 59.2 }, { 62, 13.6, 58.6 }, { 48, 13.9, 57.9 },
				{ 34, 14.1, 57.0 }, { 20, 14.4, 56.1 }, { 9, 14.6, 55.4 }, { 2, 14.8, 54.6 } };
		for (double[] point : pumpTrend) {
			boolean recent = point[0] <= 9;
			check(gridPump, now.minus(Duration.ofDays((long) point[0])).minus(Duration.ofHours(10)),
					recent ? OverallCondition.FAIR : OverallCondition.GOOD, 3.1 - (90 - point[0]) * 0.004, point[2],
					point[1], null, recent ? VibrationLevel.ELEVATED : VibrationLevel.NORMAL, false, null, null,
					recent ? "Bearing noise audible at the motor end." : null, owner);
			checkCount++;
		}
		// F-1: pressure drop across the filter climbing as the dam water turns silty.
		double[][] filterTrend = { { 42, 0.21 }, { 35, 0.27 }, { 28, 0.33 }, { 21, 0.41 }, { 14, 0.48 }, { 7, 0.56 },
				{ 1, 0.64 } };
		for (double[] point : filterTrend) {
			check(filter, now.minus(Duration.ofDays((long) point[0])), OverallCondition.GOOD, null, null, null,
					point[1], VibrationLevel.NORMAL, false, null, null, null, owner);
			checkCount++;
		}
		// BH-1: water table falling through the dry season.
		double[][] levelTrend = { { 80, 42.1 }, { 60, 42.8 }, { 40, 43.5 }, { 20, 44.1 }, { 3, 44.7 } };
		for (double[] point : levelTrend) {
			check(borehole, now.minus(Duration.ofDays((long) point[0])), OverallCondition.GOOD, null, null, null, null,
					null, false, point[1], null, null, owner);
			checkCount++;
		}
		// Healthy references.
		check(solarPump, now.minus(Duration.ofDays(12)), OverallCondition.GOOD, 8.6, 17.6, 7.3, null,
				VibrationLevel.NORMAL, false, null, null, null, owner);
		check(pivotPump, now.minus(Duration.ofDays(16)), OverallCondition.GOOD, 6.8, 149.0, 44.2, null,
				VibrationLevel.NORMAL, false, null, null, null, owner);
		check(pivot, now.minus(Duration.ofDays(20)), OverallCondition.GOOD, null, null, null, null, null, false, null,
				null, "Quarterly inspection: tyres and gearbox oil OK.", owner);
		check(dripB, now.minus(Duration.ofDays(4)), OverallCondition.FAIR, 1.1, null, null, null, null, true, null,
				null, "Manifold valve weeping; 3 emitter lines blocked at the tail end.", owner);
		checkCount += 4;

		// ---- Irrigation history from the water-balance engine ----
		LocalDate historyFrom = today.minusDays(70);
		List<WeatherDay> weatherDays = weather.series(farm, historyFrom, today.plusDays(7)).days();
		int irrigationCount = 0;
		for (int i = 0; i < plans.size(); i++) {
			irrigationCount += simulateHistory(createdFields.get(i), plans.get(i), fieldPumps[i], weatherDays, today,
					zone, owner, random, i == 1);
		}

		// ---- Maintenance history ----
		int workOrderCount = 0;
		workOrders.save(new WorkOrder(farm, filter, null, new WorkOrderDetails(
				WorkOrderKind.PREVENTIVE, WorkOrderPriority.HIGH, "Clean and inspect disc filter elements",
				"Differential pressure has passed 0.5 bar. Backflush, then open and brush the disc stacks.",
				today.plusDays(2), "T. Moyo"), owner.getId()));
		workOrderCount++;
		WorkOrder valveRepair = workOrders.save(new WorkOrder(farm, dripB, null, new WorkOrderDetails(
				WorkOrderKind.CORRECTIVE, WorkOrderPriority.MEDIUM, "Replace weeping valve on Block B manifold",
				"Found during inspection. Also clear 3 blocked emitter lines at the tail end.", today.plusDays(1),
				"R. Chikore"), owner.getId()));
		valveRepair.start(now.minus(Duration.ofHours(20)));
		workOrderCount++;
		WorkOrder pivotInspection = workOrders.save(new WorkOrder(farm, pivot, null, new WorkOrderDetails(
				WorkOrderKind.INSPECTION, WorkOrderPriority.MEDIUM, "Quarterly pivot inspection",
				"Tyres, gearbox oil, span flex joints.", today.minusDays(20), "Valley dealer"), owner.getId()));
		pivotInspection.complete("All towers OK; topped up gearbox oil on tower 3.", new BigDecimal("45.00"),
				new BigDecimal("2.00"), now.minus(Duration.ofDays(20)));
		workOrderCount++;
		WorkOrder panelClean = workOrders.save(new WorkOrder(farm, solarArray, null, new WorkOrderDetails(
				WorkOrderKind.PREVENTIVE, WorkOrderPriority.LOW, "Clean solar panels", null, today.minusDays(38),
				"Farm team"), owner.getId()));
		panelClean.complete("Washed with soft brushes at dawn.", new BigDecimal("0.00"), null,
				now.minus(Duration.ofDays(38)));
		workOrderCount++;

		audit.record(AuditEntry.of(AuditAction.DEMO_FARM_CREATED)
			.target("FARM", farm.getId())
			.detail("owner", owner.getEmail()));
		farms.flush();
		AlertEvaluator.Outcome outcome = alerts.evaluate(farm);
		return new DemoFarmResult(farm.getId(), farm.getName(), owner.getEmail(), plans.size(),
				(int) assets.countByFarmId(farm.getId()), irrigationCount, checkCount, workOrderCount,
				outcome.raised() + outcome.updated());
	}

	/**
	 * Irrigates like a careful manager: drip little and often, overhead systems in bigger doses, each
	 * run limited to the pump's power window. Block B (served by the failing P-2) has had no water
	 * for five days.
	 */
	private int simulateHistory(Field field, FieldPlan plan, Asset pump, List<WeatherDay> weatherDays,
			LocalDate today, ZoneId zone, User owner, SplittableRandom random, boolean lagging) {
		LocalDate planting = today.minusDays(plan.daysSincePlanting());
		List<WaterBalanceEngine.WeatherInput> weatherInputs = weatherDays.stream()
			.map((d) -> new WaterBalanceEngine.WeatherInput(d.date(), d.et0Mm(), d.precipitationMm(),
					d.precipitationProbability(), false))
			.toList();
		List<WaterBalanceEngine.IrrigationInput> applied = new ArrayList<>();
		LocalDate start = planting.isAfter(today.minusDays(60)) ? planting.plusDays(1) : today.minusDays(60);
		int count = 0;
		LocalDate stopFrom = lagging ? today.minusDays(5) : today;
		for (LocalDate day = start; day.isBefore(stopFrom); day = day.plusDays(1)) {
			LocalDate asOf = day;
			WaterBalanceEngine.Result result = WaterBalanceEngine.run(new WaterBalanceEngine.Input(asOf,
					plan.areaHa(), plan.soil(), new WaterBalanceEngine.CropInput(plan.crop(), planting, plan.yieldTHa()),
					plan.method(), plan.rateMmH(), plan.efficiency(),
					weatherInputs.stream().filter((w) -> !w.date().isAfter(asOf)).toList(), applied, List.of()));
			if (result.currentDepletionMm() < triggerFraction(plan.method()) * result.currentRawMm()) {
				continue;
			}
			double gross = Math.min(result.currentDepletionMm(), plan.method().maxNetApplicationMm()) / plan.efficiency();
			int duration = (int) Math.min(windowMinutes(pump), Math.ceil(gross / plan.rateMmH() * 60));
			gross = duration * plan.rateMmH() / 60.0;
			applied.add(new WaterBalanceEngine.IrrigationInput(day, gross, false));
			LocalTime startTime = (pump.getOperatingWindowStart() != null) ? pump.getOperatingWindowStart()
					: LocalTime.of(6, 0);
			Instant startedAt = day.atTime(startTime).plusMinutes(random.nextInt(20)).atZone(zone).toInstant();
			double energy = pump.getRatedPowerKw().doubleValue() * duration / 60.0;
			irrigations.save(new IrrigationEvent(field, IrrigationStatus.COMPLETED,
					(random.nextInt(5) == 0) ? IrrigationSource.MANUAL : IrrigationSource.RECOMMENDATION, startedAt,
					duration, gross, pump.getId(), energy, null, owner.getId(),
					startedAt.plus(Duration.ofMinutes(duration))));
			count++;
		}
		if (lagging) {
			Instant planned = today.minusDays(2).atTime(22, 0).atZone(zone).toInstant();
			IrrigationEvent skipped = irrigations.save(new IrrigationEvent(field, IrrigationStatus.PLANNED,
					IrrigationSource.RECOMMENDATION, planned, 240, 12.0, pump.getId(), 60.0, null, owner.getId(),
					planned));
			skipped.skip("P-2 tripped on overload after 10 minutes; restarted twice.");
			count++;
		}
		// A farmer's feel check two days ago, consistent with the modelled soil water.
		LocalDate observed = today.minusDays(2);
		WaterBalanceEngine.Result atCheck = WaterBalanceEngine.run(new WaterBalanceEngine.Input(observed,
				plan.areaHa(), plan.soil(), new WaterBalanceEngine.CropInput(plan.crop(), planting, plan.yieldTHa()),
				plan.method(), plan.rateMmH(), plan.efficiency(),
				weatherInputs.stream().filter((w) -> !w.date().isAfter(observed)).toList(), applied, List.of()));
		double pct = Math.round(atCheck.depletionPct() / 5.0) * 5.0;
		observations.save(new SoilObservation(field, observed.atTime(7, 30).atZone(zone).toInstant(),
				Math.max(0, Math.min(100, pct)), ObservationMethod.FEEL, "Hand-feel test at 20 cm", owner.getId(),
				clock.instant()));
		return count;
	}

	private static double triggerFraction(IrrigationMethod method) {
		return switch (method) {
			case DRIP, MICRO_SPRINKLER -> 0.45;
			case CENTER_PIVOT -> 0.6;
			default -> 0.8;
		};
	}

	private static int windowMinutes(Asset pump) {
		if (pump.getOperatingWindowStart() == null || pump.getOperatingWindowEnd() == null) {
			return 16 * 60;
		}
		long minutes = java.time.Duration.between(pump.getOperatingWindowStart(), pump.getOperatingWindowEnd()).toMinutes();
		return (int) ((minutes <= 0) ? minutes + 24 * 60 : minutes);
	}

	private Asset asset(Farm farm, AssetType type, String name, UUID parentId, UUID fieldId, String manufacturer,
			String model, LocalDate installedOn, Criticality criticality, PowerSource power, BigDecimal ratedPowerKw,
			BigDecimal ratedFlowM3h, BigDecimal ratedHeadM, BigDecimal capacityM3, BigDecimal allocationM3,
			LocalTime windowStart, LocalTime windowEnd, Integer serviceDays, Integer serviceHours, Integer lifeYears,
			LocalDate lastService, BigDecimal baselineEnergy, double hoursAtLastService, String notes) {
		Asset asset = assets.save(new Asset(farm,
				new AssetDetails(type, name, fieldId, parentId, manufacturer, model, null, installedOn, criticality,
						AssetStatus.OPERATIONAL, power, ratedPowerKw, ratedFlowM3h, ratedHeadM, capacityM3,
						allocationM3, windowStart, windowEnd, serviceDays, serviceHours, lifeYears, lastService,
						baselineEnergy, BigDecimal.valueOf(hoursAtLastService), notes)));
		if (lastService != null) {
			asset.recordService(lastService);
		}
		return asset;
	}

	private void check(Asset asset, Instant at, OverallCondition condition, Double pressure, Double flow, Double power,
			Double filterDp, VibrationLevel vibration, boolean leak, Double waterLevel, Double hourMeter, String notes,
			User owner) {
		checks.save(new AssetConditionCheck(asset,
				new CheckDetails(at, condition, decimal(pressure), decimal(flow), decimal(power), decimal(hourMeter),
						vibration, leak, decimal(filterDp), decimal(waterLevel), notes),
				owner.getId(), at));
	}

	private static BigDecimal decimal(Double value) {
		return (value != null) ? BigDecimal.valueOf(Math.round(value * 100) / 100.0) : null;
	}

	private static GrowthStage stageFor(CropType crop, int daysSincePlanting) {
		return switch (com.irrigaiq.agronomy.CropCatalog.parameters(crop).phaseAt(daysSincePlanting)) {
			case INITIAL -> GrowthStage.INITIAL;
			case DEVELOPMENT -> GrowthStage.DEVELOPMENT;
			case MID_SEASON -> GrowthStage.MID_SEASON;
			case LATE_SEASON, HARVEST -> GrowthStage.LATE_SEASON;
		};
	}

}
