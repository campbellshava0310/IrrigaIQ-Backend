package com.irrigaiq.intelligence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.irrigaiq.agronomy.GrowthPhase;
import com.irrigaiq.agronomy.WaterBalanceEngine.Decision;
import com.irrigaiq.agronomy.WaterBalanceEngine.Recommendation;
import com.irrigaiq.agronomy.WaterBalanceEngine.WaterStatus;
import com.irrigaiq.alerts.AlertService.AlertSummary;
import com.irrigaiq.assets.AssetHealthEngine.Factor;
import com.irrigaiq.assets.AssetStatus;
import com.irrigaiq.assets.AssetType;
import com.irrigaiq.assets.AssetViews.AssetHealthSnapshot;
import com.irrigaiq.assets.RiskLevel;
import com.irrigaiq.common.text.HumanText;
import com.irrigaiq.farm.CropType;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.irrigation.IrrigationEvent;
import com.irrigaiq.irrigation.IrrigationService.FieldWaterState;
import com.irrigaiq.maintenance.WorkOrderPriority;
import com.irrigaiq.maintenance.WorkOrderService.WorkOrderView;
import com.irrigaiq.maintenance.WorkOrderStatus;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.water.WaterService.WaterSummary;
import com.irrigaiq.weather.WeatherDay;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** "What should I do today?": the farm command centre for web and mobile. */
@Service
public class DashboardService {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

	private final FarmService farms;

	private final FarmSnapshot.Assembler assembler;

	public DashboardService(FarmService farms, FarmSnapshot.Assembler assembler) {
		this.farms = farms;
		this.assembler = assembler;
	}

	public enum TaskKind {

		IRRIGATE, CONFIRM_IRRIGATION, WORK_ORDER, BOOK_SERVICE

	}

	/** @param priority 1 = do first */
	public record Task(TaskKind kind, int priority, String title, String detail, LocalDate dueOn, UUID fieldId,
			UUID assetId, UUID workOrderId, UUID irrigationEventId) {
	}

	public record FieldTile(UUID fieldId, String name, CropType cropType, GrowthPhase phase, WaterStatus status,
			Decision decision, double depletionPct, double stressThresholdPct, LocalDate irrigateOn, double grossDepthMm,
			double volumeM3, int durationMinutes, LocalTime startTime, Instant nextPlannedAt, Double yieldAtRiskPct) {
	}

	public record AssetTile(UUID assetId, String name, AssetType type, AssetStatus status, int healthScore,
			RiskLevel risk, String topFactor, LocalDate nextServiceOn) {
	}

	public record FieldSummary(int total, int planted, Map<WaterStatus, Integer> byStatus, List<FieldTile> items) {
	}

	public record WaterKpis(double weekAppliedM3, double weekRequirementM3, Double weekAdequacyPct,
			double monthSavedM3, Double monthSavedPct, double weekEnergyKwh, Double weekEnergyCost, String currency,
			String baselineDescription) {
	}

	public record AssetSummary(int total, Map<RiskLevel, Integer> byRisk, int down, int serviceOverdue,
			int serviceDueSoon, List<AssetTile> attention) {
	}

	public record MaintenanceSummary(int open, int inProgress, int overdue, int dueThisWeek) {
	}

	public record Dashboard(UUID farmId, String farmName, String location, LocalDate today, String timezone,
			boolean weatherLive, List<WeatherDay> forecast, List<Task> tasks, FieldSummary fields, WaterKpis water,
			AssetSummary assets, MaintenanceSummary maintenance, AlertSummary alerts) {
	}

	@Transactional(readOnly = true)
	public Dashboard dashboard(UUID farmId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		FarmSnapshot snapshot = assembler.assemble(farm);
		return new Dashboard(farm.getId(), farm.getName(), farm.getLocation(), snapshot.today(), farm.getTimezone(),
				snapshot.weather().live(), snapshot.weather().days(), tasks(snapshot), fields(snapshot),
				water(snapshot), assets(snapshot), maintenance(snapshot), snapshot.alerts());
	}

	static List<Task> tasks(FarmSnapshot snapshot) {
		List<Task> tasks = new ArrayList<>();
		ZoneId zone = snapshot.farm().zone();
		for (FieldWaterState state : snapshot.fields()) {
			Recommendation rec = state.recommendation();
			String field = state.field().getName();
			if (rec.decision() == Decision.IRRIGATE_NOW || rec.decision() == Decision.IRRIGATE_SOON) {
				boolean stressed = rec.status() == WaterStatus.STRESSED;
				String when = (rec.decision() == Decision.IRRIGATE_NOW) ? "today" : "by " + DAY.format(rec.irrigateOn());
				tasks.add(new Task(TaskKind.IRRIGATE, stressed ? 1 : (rec.decision() == Decision.IRRIGATE_NOW) ? 2 : 3,
						"Irrigate " + field + " " + when,
						String.format(Locale.ENGLISH, "%.0f mm · %.0f m³ · %s from %s", rec.grossDepthMm(),
								rec.volumeM3(), HumanText.duration(rec.durationMinutes()), state.schedule().startTime()),
						rec.irrigateOn(), state.field().getId(), null, null, null));
			}
			IrrigationEvent planned = state.nextPlanned();
			if (planned != null && !LocalDate.ofInstant(planned.getStartedAt(), zone).isAfter(snapshot.today())) {
				tasks.add(new Task(TaskKind.CONFIRM_IRRIGATION, 2, "Confirm irrigation of " + field,
						"Planned for " + LocalTime.ofInstant(planned.getStartedAt(), zone) + ". Log it once it has run.",
						snapshot.today(), state.field().getId(), null, null, planned.getId()));
			}
		}
		for (WorkOrderView workOrder : snapshot.workOrders()) {
			if (!workOrder.status().isActive()) {
				continue;
			}
			boolean dueSoon = workOrder.dueOn() != null && !workOrder.dueOn().isAfter(snapshot.today().plusDays(3));
			if (workOrder.overdue() || dueSoon || workOrder.priority() == WorkOrderPriority.URGENT) {
				int priority = (workOrder.priority() == WorkOrderPriority.URGENT || workOrder.overdue()) ? 1 : 2;
				tasks.add(new Task(TaskKind.WORK_ORDER, priority, workOrder.reference() + ": " + workOrder.title(),
						(workOrder.asset() != null) ? workOrder.asset().name() : "General maintenance",
						workOrder.dueOn(), null, (workOrder.asset() != null) ? workOrder.asset().id() : null,
						workOrder.id(), null));
			}
		}
		for (AssetHealthSnapshot asset : snapshot.assets()) {
			var service = asset.health().service();
			boolean covered = snapshot.workOrders()
				.stream()
				.anyMatch((w) -> w.status().isActive() && w.asset() != null
						&& w.asset().id().equals(asset.asset().getId()));
			if (service != null && service.overdue() && !covered) {
				tasks.add(new Task(TaskKind.BOOK_SERVICE, 2, "Book service for " + asset.asset().getName(),
						"Service overdue. " + asset.health().recommendedAction(), service.dueOn(), null,
						asset.asset().getId(), null, null));
			}
		}
		tasks.sort(Comparator.comparingInt(Task::priority)
			.thenComparing(Task::dueOn, Comparator.nullsLast(Comparator.naturalOrder())));
		return tasks;
	}

	private static FieldSummary fields(FarmSnapshot snapshot) {
		Map<WaterStatus, Integer> byStatus = new EnumMap<>(WaterStatus.class);
		List<FieldTile> tiles = new ArrayList<>();
		int planted = 0;
		for (FieldWaterState state : snapshot.fields()) {
			Recommendation rec = state.recommendation();
			byStatus.merge(rec.status(), 1, Integer::sum);
			if (state.crop() != null) {
				planted++;
			}
			var season = state.result().season();
			tiles.add(new FieldTile(state.field().getId(), state.field().getName(),
					(state.crop() != null) ? state.crop().getCropType() : null,
					(season != null) ? season.phase() : null, rec.status(), rec.decision(),
					round(state.result().depletionPct()), round(state.result().stressThresholdPct()), rec.irrigateOn(),
					round(rec.grossDepthMm()), round(rec.volumeM3()), rec.durationMinutes(),
					state.schedule().startTime(),
					(state.nextPlanned() != null) ? state.nextPlanned().getStartedAt() : null,
					(season != null) ? round(season.yieldReductionPct()) : null));
		}
		tiles.sort(Comparator.comparingInt((FieldTile tile) -> urgency(tile.status())).thenComparing(FieldTile::name));
		return new FieldSummary(snapshot.fields().size(), planted, byStatus, tiles);
	}

	private static WaterKpis water(FarmSnapshot snapshot) {
		WaterSummary week = snapshot.week();
		WaterSummary month = snapshot.month();
		return new WaterKpis(week.appliedM3(), week.requirementM3(), week.adequacyPct(), month.savedM3(),
				month.savedPct(), week.energyKwh(), week.energyCost(), week.currency(), month.baselineDescription());
	}

	private static AssetSummary assets(FarmSnapshot snapshot) {
		Map<RiskLevel, Integer> byRisk = new EnumMap<>(RiskLevel.class);
		int down = 0;
		int overdue = 0;
		int dueSoon = 0;
		List<AssetTile> attention = new ArrayList<>();
		for (AssetHealthSnapshot s : snapshot.assets()) {
			if (s.asset().getStatus() == AssetStatus.RETIRED) {
				continue;
			}
			byRisk.merge(s.health().risk(), 1, Integer::sum);
			if (s.asset().getStatus() == AssetStatus.DOWN) {
				down++;
			}
			var service = s.health().service();
			if (service != null && service.overdue()) {
				overdue++;
			}
			else if (service != null && service.daysRemaining() != null && service.daysRemaining() <= 14) {
				dueSoon++;
			}
			Factor top = s.health().factors().stream().filter((f) -> f.impact() < 0).findFirst().orElse(null);
			attention.add(new AssetTile(s.asset().getId(), s.asset().getName(), s.asset().getType(),
					s.asset().getStatus(), s.health().score(), s.health().risk(), (top != null) ? top.label() : null,
					(service != null) ? service.dueOn() : null));
		}
		attention.sort(Comparator.comparingInt(AssetTile::healthScore));
		return new AssetSummary(attention.size(), byRisk, down, overdue, dueSoon,
				attention.stream().filter((tile) -> tile.risk() != RiskLevel.LOW).limit(4).toList());
	}

	private static MaintenanceSummary maintenance(FarmSnapshot snapshot) {
		int open = 0;
		int inProgress = 0;
		int overdue = 0;
		int dueThisWeek = 0;
		for (WorkOrderView workOrder : snapshot.workOrders()) {
			if (!workOrder.status().isActive()) {
				continue;
			}
			if (workOrder.status() == WorkOrderStatus.IN_PROGRESS) {
				inProgress++;
			}
			else {
				open++;
			}
			if (workOrder.overdue()) {
				overdue++;
			}
			else if (workOrder.dueOn() != null && !workOrder.dueOn().isAfter(snapshot.today().plusDays(7))) {
				dueThisWeek++;
			}
		}
		return new MaintenanceSummary(open, inProgress, overdue, dueThisWeek);
	}

	static int urgency(WaterStatus status) {
		return switch (status) {
			case STRESSED -> 0;
			case IRRIGATE_NOW -> 1;
			case IRRIGATE_SOON -> 2;
			case ADEQUATE -> 3;
			case SEASON_ENDED -> 4;
			case NOT_PLANTED -> 5;
		};
	}

	static double round(double value) {
		return Math.round(value * 10) / 10.0;
	}

}
