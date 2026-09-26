package com.irrigaiq.assets;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.irrigaiq.common.text.HumanText;

/**
 * Rule-based asset health and service prediction. Every point deducted is reported as a factor
 * with the action that would recover it, so the score is always explainable. The same contract is
 * later fulfilled by sensor-driven anomaly detection and remaining-useful-life models.
 *
 * <p>Thresholds follow common practice for irrigation pumping: centrifugal pumps lose 10-25 %
 * efficiency over their life, so a sustained rise in energy per cubic metre is the earliest
 * reliable degradation signal; screen/disc filters are cleaned at about 0.5 bar differential.
 */
public final class AssetHealthEngine {

	public static final String MODEL_VERSION = "rules-1";

	static final int DUE_SOON_DAYS = 14;

	static final int STALE_CHECK_DAYS = 60;

	private AssetHealthEngine() {
	}

	public record CheckInput(LocalDate date, OverallCondition condition, VibrationLevel vibration, boolean leakDetected,
			Double filterDifferentialBar, Double powerKw, Double flowM3h, Double waterLevelM) {
	}

	public record Input(LocalDate today, AssetType type, AssetStatus status, LocalDate installedOn,
			Integer expectedLifeYears, Integer serviceIntervalDays, Integer serviceIntervalHours, double operatingHours,
			double hoursAtLastService, LocalDate lastServiceOn, Double baselineEnergyKwhPerM3, Double ratedPowerKw,
			Double ratedFlowM3h, List<CheckInput> recentChecks, int openCorrectiveWorkOrders,
			double averageRunHoursPerDay) {
	}

	public enum Severity {

		INFO, WARNING, CRITICAL

	}

	public record Factor(String code, String label, String detail, int impact, Severity severity, String action) {
	}

	public record ServiceForecast(LocalDate dueOn, String basis, Integer daysRemaining, Double runHoursRemaining,
			boolean overdue, double progressPct) {
	}

	public record Health(int score, RiskLevel risk, List<Factor> factors, ServiceForecast service,
			Double energyKwhPerM3, Double energyDriftPct, String outlook, String recommendedAction) {
	}

	public static Health assess(Input in) {
		List<Factor> factors = new ArrayList<>();
		CheckInput latest = in.recentChecks().stream().max(Comparator.comparing(CheckInput::date)).orElse(null);

		switch (in.status()) {
			case DOWN -> factors.add(new Factor("ASSET_DOWN", "Out of service", "Marked as down by the farm team.",
					-60, Severity.CRITICAL, "Restore the asset or bring standby equipment online."));
			case DEGRADED -> factors.add(new Factor("DEGRADED", "Running degraded",
					"Marked as degraded by the farm team.", -25, Severity.WARNING,
					"Plan a corrective work order before it fails."));
			default -> {
			}
		}

		ServiceForecast service = serviceForecast(in, factors);
		ageFactor(in, factors);
		Double[] energy = energyFactor(in, latest, factors);
		conditionFactors(in, latest, factors);

		if (in.openCorrectiveWorkOrders() > 0) {
			factors.add(new Factor("OPEN_REPAIRS", "Open repairs",
					in.openCorrectiveWorkOrders()
							+ ((in.openCorrectiveWorkOrders() == 1) ? " corrective work order" : " corrective work orders")
							+ " still open.",
					-Math.min(16, 8 * in.openCorrectiveWorkOrders()), Severity.WARNING,
					"Close out the open corrective work."));
		}

		int score = 100 + factors.stream().mapToInt(Factor::impact).sum();
		score = Math.max(0, Math.min(100, score));
		if (in.status() == AssetStatus.DOWN) {
			score = Math.min(score, 20);
		}
		RiskLevel risk = (score >= 80) ? RiskLevel.LOW
				: (score >= 60) ? RiskLevel.MODERATE : (score >= 40) ? RiskLevel.HIGH : RiskLevel.CRITICAL;

		factors.sort(Comparator.comparingInt(Factor::impact));
		String action = factors.stream()
			.filter((factor) -> factor.impact() < 0)
			.map(Factor::action)
			.findFirst()
			.orElse((service != null && service.dueOn() != null)
					? "No action needed before the next service on " + service.dueOn() + "."
					: "No action needed. Record a condition check each month to keep the forecast reliable.");
		return new Health(score, risk, List.copyOf(factors), service, energy[0], energy[1], outlook(risk), action);
	}

	private static ServiceForecast serviceForecast(Input in, List<Factor> factors) {
		LocalDate byCalendar = null;
		double calendarProgress = 0;
		if (in.serviceIntervalDays() != null) {
			LocalDate reference = (in.lastServiceOn() != null) ? in.lastServiceOn() : in.installedOn();
			if (reference != null) {
				byCalendar = reference.plusDays(in.serviceIntervalDays());
				calendarProgress = 100.0 * ChronoUnit.DAYS.between(reference, in.today()) / in.serviceIntervalDays();
			}
		}
		LocalDate byHours = null;
		Double hoursRemaining = null;
		double hoursProgress = 0;
		if (in.serviceIntervalHours() != null) {
			double hoursSince = Math.max(0, in.operatingHours() - in.hoursAtLastService());
			hoursRemaining = in.serviceIntervalHours() - hoursSince;
			hoursProgress = 100.0 * hoursSince / in.serviceIntervalHours();
			if (hoursRemaining <= 0) {
				byHours = in.today();
			}
			else if (in.averageRunHoursPerDay() > 0.05) {
				byHours = in.today().plusDays((long) Math.ceil(hoursRemaining / in.averageRunHoursPerDay()));
			}
		}
		if (byCalendar == null && byHours == null && in.serviceIntervalHours() == null) {
			if (in.serviceIntervalDays() == null) {
				factors.add(new Factor("NO_SERVICE_PLAN", "No service plan",
						"No service interval is set, so failures cannot be anticipated.", -5, Severity.INFO,
						"Set the manufacturer's service interval (days or run-hours)."));
			}
			return null;
		}

		boolean hoursBasis = byHours != null && (byCalendar == null || byHours.isBefore(byCalendar));
		LocalDate dueOn = hoursBasis ? byHours : byCalendar;
		double progress = Math.max(calendarProgress, hoursProgress);
		Integer daysRemaining = (dueOn != null) ? (int) ChronoUnit.DAYS.between(in.today(), dueOn) : null;
		boolean overdue = (daysRemaining != null && daysRemaining < 0) || (hoursRemaining != null && hoursRemaining <= 0);

		if (overdue) {
			long overdueDays = (daysRemaining != null) ? Math.max(0, -daysRemaining) : 0;
			double overdueHours = (hoursRemaining != null) ? Math.max(0, -hoursRemaining) : 0;
			int impact = (int) -Math.min(30, 12 + overdueDays / 3 + overdueHours / 25);
			String detail = (overdueHours > 0 && hoursBasis)
					? String.format(Locale.ENGLISH, "%.0f run-hours past the service interval.", overdueHours)
					: "Service was due " + HumanText.days(overdueDays) + " ago.";
			factors.add(new Factor("SERVICE_OVERDUE", "Service overdue", detail, impact,
					(impact <= -20) ? Severity.CRITICAL : Severity.WARNING, "Schedule the preventive service now."));
		}
		else if (daysRemaining != null && daysRemaining <= DUE_SOON_DAYS) {
			factors.add(new Factor("SERVICE_DUE_SOON", "Service due soon",
					"Next service due in " + HumanText.days(daysRemaining) + ".", -4, Severity.INFO,
					"Book the preventive service and order parts."));
		}
		else if (hoursRemaining != null && hoursRemaining <= 0.1 * in.serviceIntervalHours()) {
			factors.add(new Factor("SERVICE_DUE_SOON", "Service due soon",
					String.format(Locale.ENGLISH, "%.0f run-hours left before the next service.", hoursRemaining), -4,
					Severity.INFO, "Book the preventive service and order parts."));
		}
		return new ServiceForecast(dueOn, hoursBasis ? "RUN_HOURS" : "CALENDAR", daysRemaining,
				(hoursRemaining != null) ? round(hoursRemaining, 1) : null, overdue, round(Math.min(150, progress), 1));
	}

	private static void ageFactor(Input in, List<Factor> factors) {
		if (in.installedOn() == null || in.expectedLifeYears() == null) {
			return;
		}
		double ageYears = ChronoUnit.DAYS.between(in.installedOn(), in.today()) / 365.25;
		double lifeUsed = ageYears / in.expectedLifeYears();
		if (lifeUsed >= 1.0) {
			factors.add(new Factor("END_OF_LIFE", "Past expected life",
					String.format(Locale.ENGLISH, "%.1f years old against a %d-year design life.", ageYears,
							in.expectedLifeYears()),
					-20, Severity.WARNING, "Budget for replacement and keep critical spares on hand."));
		}
		else if (lifeUsed >= 0.8) {
			factors.add(new Factor("AGEING", "Approaching end of life",
					String.format(Locale.ENGLISH, "%.0f%% of its design life used.", lifeUsed * 100), -8,
					Severity.INFO, "Plan replacement within the next budget cycle."));
		}
	}

	/** @return [latest kWh/m³, drift % against baseline] (either may be null) */
	private static Double[] energyFactor(Input in, CheckInput latest, List<Factor> factors) {
		if (in.type() != AssetType.PUMP || latest == null || latest.powerKw() == null || latest.flowM3h() == null
				|| latest.flowM3h() <= 0) {
			return new Double[] { null, null };
		}
		double specificEnergy = latest.powerKw() / latest.flowM3h();
		Double baseline = in.baselineEnergyKwhPerM3();
		if (baseline == null && in.ratedPowerKw() != null && in.ratedFlowM3h() != null) {
			baseline = in.ratedPowerKw() / in.ratedFlowM3h();
		}
		if (baseline == null) {
			return new Double[] { round(specificEnergy, 3), null };
		}
		double drift = (specificEnergy / baseline - 1) * 100;
		if (drift >= 20) {
			factors.add(new Factor("ENERGY_DRIFT", "Energy use per m³ up sharply",
					String.format(Locale.ENGLISH, "%.3f kWh/m³ vs %.3f baseline (+%.0f%%).", specificEnergy, baseline,
							drift),
					-25, Severity.CRITICAL,
					"Inspect impeller and wear rings, check for suction-side blockage or cavitation."));
		}
		else if (drift >= 10) {
			factors.add(new Factor("ENERGY_DRIFT", "Energy use per m³ rising",
					String.format(Locale.ENGLISH, "%.3f kWh/m³ vs %.3f baseline (+%.0f%%).", specificEnergy, baseline,
							drift),
					-12, Severity.WARNING, "Schedule a pump performance test at the next service."));
		}
		return new Double[] { round(specificEnergy, 3), round(drift, 1) };
	}

	private static double round(double value, int decimals) {
		double scale = Math.pow(10, decimals);
		return Math.round(value * scale) / scale;
	}

	private static void conditionFactors(Input in, CheckInput latest, List<Factor> factors) {
		if (latest == null) {
			factors.add(new Factor("NO_CONDITION_DATA", "No recent condition check",
					"No inspection recorded in the last " + STALE_CHECK_DAYS + " days.", -5, Severity.INFO,
					"Record a condition check to improve the forecast."));
			return;
		}
		long checkAge = ChronoUnit.DAYS.between(latest.date(), in.today());
		if (checkAge > STALE_CHECK_DAYS) {
			factors.add(new Factor("STALE_CONDITION_DATA", "Condition data is stale",
					"Last inspection " + checkAge + " days ago.", -5, Severity.INFO, "Record a fresh condition check."));
		}
		if (latest.condition() == OverallCondition.POOR) {
			factors.add(new Factor("POOR_CONDITION", "Inspected in poor condition",
					"Last inspection rated the asset as poor.", -25, Severity.CRITICAL,
					"Raise a corrective work order from the inspection findings."));
		}
		else if (latest.condition() == OverallCondition.FAIR) {
			factors.add(new Factor("FAIR_CONDITION", "Inspected in fair condition",
					"Last inspection noted wear or minor defects.", -10, Severity.WARNING,
					"Address the noted defects at the next service."));
		}
		if (latest.vibration() == VibrationLevel.SEVERE) {
			factors.add(new Factor("VIBRATION", "Severe vibration", "Severe vibration reported at last check.", -25,
					Severity.CRITICAL, "Stop and inspect bearings, coupling alignment and mounting."));
		}
		else if (latest.vibration() == VibrationLevel.ELEVATED) {
			factors.add(new Factor("VIBRATION", "Elevated vibration", "Elevated vibration reported at last check.",
					-10, Severity.WARNING, "Check bearings and coupling alignment at the next service."));
		}
		if (latest.leakDetected()) {
			factors.add(new Factor("LEAK", "Leak reported", "A leak was observed at the last check.", -15,
					Severity.WARNING, "Repair the leak: it wastes water and pumping energy."));
		}
		if (latest.filterDifferentialBar() != null && latest.filterDifferentialBar() >= 0.5) {
			boolean severe = latest.filterDifferentialBar() >= 0.8;
			factors.add(new Factor("FILTER_CLOGGING", severe ? "Filter heavily clogged" : "Filter needs cleaning",
					String.format(Locale.ENGLISH, "Differential pressure %.2f bar (clean below 0.5).",
							latest.filterDifferentialBar()),
					severe ? -25 : -15, severe ? Severity.CRITICAL : Severity.WARNING,
					"Backflush or clean the filter elements; check upstream sediment."));
		}
		waterLevelFactor(in, factors);
	}

	private static void waterLevelFactor(Input in, List<Factor> factors) {
		if (in.type() != AssetType.BOREHOLE) {
			return;
		}
		List<CheckInput> levels = in.recentChecks()
			.stream()
			.filter((check) -> check.waterLevelM() != null)
			.sorted(Comparator.comparing(CheckInput::date))
			.toList();
		if (levels.size() < 2) {
			return;
		}
		// Water level is recorded as depth below ground: a larger number means a lower water table.
		double drop = levels.get(levels.size() - 1).waterLevelM() - levels.get(0).waterLevelM();
		if (drop >= 2.0) {
			factors.add(new Factor("WATER_LEVEL_FALLING", "Water table falling",
					String.format(Locale.ENGLISH, "Level dropped %.1f m over %d days.", drop,
							ChronoUnit.DAYS.between(levels.get(0).date(), levels.get(levels.size() - 1).date())),
					-10, Severity.WARNING,
					"Reduce abstraction rate and check pump intake depth before it runs dry."));
		}
	}

	private static String outlook(RiskLevel risk) {
		return switch (risk) {
			case LOW -> "No problems expected before the next scheduled service.";
			case MODERATE -> "Some wear signals. Address them at the next service to avoid escalation.";
			case HIGH -> "Elevated risk of breakdown in the coming weeks without intervention.";
			case CRITICAL -> "Failure is likely or already happening. Act now to protect irrigation.";
		};
	}

}
