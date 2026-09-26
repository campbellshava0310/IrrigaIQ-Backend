package com.irrigaiq.water;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.irrigaiq.agronomy.WaterBalanceEngine.Day;
import com.irrigaiq.assets.Asset;
import com.irrigaiq.assets.AssetRepository;
import com.irrigaiq.assets.AssetType;
import com.irrigaiq.assets.PowerSource;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.irrigation.IrrigationEvent;
import com.irrigaiq.irrigation.IrrigationService;
import com.irrigaiq.irrigation.IrrigationService.FieldWaterState;
import com.irrigaiq.irrigation.IrrigationStatus;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Water accounting: what was applied, what the crops actually needed, what a conventional
 * calendar schedule would have used (the savings baseline), what it cost, and how much of each
 * source's licensed allocation has been drawn this year.
 */
@Service
public class WaterService {

	static final int DEFAULT_RANGE_DAYS = 30;

	static final int MAX_RANGE_DAYS = 180;

	private final FarmService farms;

	private final IrrigationService irrigation;

	private final AssetRepository assets;

	private final Clock clock;

	public WaterService(FarmService farms, IrrigationService irrigation, AssetRepository assets, Clock clock) {
		this.farms = farms;
		this.irrigation = irrigation;
		this.assets = assets;
		this.clock = clock;
	}

	public record FieldWater(UUID fieldId, String fieldName, double appliedM3, double requirementM3,
			double baselineM3, double savedM3, int irrigations) {
	}

	public record DailyWater(LocalDate date, double appliedM3, double requirementM3, double rainMm, double et0Mm) {
	}

	public record SourceAllocation(UUID assetId, String name, AssetType type, double annualAllocationM3,
			double usedYearToDateM3, double usedPct) {
	}

	/**
	 * @param adequacyPct applied as a share of crop requirement (100 = exactly met; above = over-irrigation)
	 * @param baselineDescription the conventional schedule savings are measured against
	 */
	public record WaterSummary(LocalDate from, LocalDate to, String currency, double appliedM3, double plannedM3,
			double requirementM3, Double adequacyPct, double baselineM3, double savedM3, Double savedPct,
			double energyKwh, Double energyCost, String baselineDescription, List<FieldWater> fields,
			List<DailyWater> daily, List<SourceAllocation> allocations, double unattributedYearToDateM3) {
	}

	@Transactional(readOnly = true)
	public WaterSummary summary(UUID farmId, AuthenticatedUser user, LocalDate from, LocalDate to) {
		Farm farm = farms.requireAccessible(farmId, user);
		LocalDate end = (to != null) ? to : LocalDate.now(clock.withZone(farm.zone()));
		LocalDate start = (from != null) ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1);
		if (end.isBefore(start) || start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
					"The range must run forwards and cover at most " + MAX_RANGE_DAYS + " days");
		}
		return summarize(farm, start, end);
	}

	/** Module API (farm already authorised). */
	@Transactional(readOnly = true)
	public WaterSummary summarize(Farm farm, LocalDate from, LocalDate to) {
		ZoneId zone = farm.zone();
		LocalDate today = LocalDate.now(clock.withZone(zone));
		LocalDate yearStart = today.withDayOfYear(1);
		LocalDate eventsFrom = from.isBefore(yearStart) ? from : yearStart;
		int historyDays = (int) Math.max(7, ChronoUnit.DAYS.between(from, today) + 1);

		List<FieldWaterState> states = irrigation.evaluate(farm, historyDays);
		List<IrrigationEvent> events = irrigation.eventsBetween(farm, eventsFrom.atStartOfDay(zone).toInstant(),
				to.plusDays(1).atStartOfDay(zone).toInstant());
		Map<UUID, Asset> assetById = assets.findByFarmIdOrderByNameAsc(farm.getId())
			.stream()
			.collect(Collectors.toMap(Asset::getId, Function.identity()));

		Map<LocalDate, double[]> daily = new LinkedHashMap<>(); // [applied, requirement, rain, et0, rainDays]
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			daily.put(date, new double[5]);
		}

		double applied = 0;
		double planned = 0;
		double energy = 0;
		double cost = 0;
		boolean anyCost = false;
		Map<UUID, double[]> perField = new LinkedHashMap<>(); // [applied, count]
		for (IrrigationEvent event : events) {
			LocalDate date = LocalDate.ofInstant(event.getStartedAt(), zone);
			boolean inPeriod = !date.isBefore(from) && !date.isAfter(to);
			if (!inPeriod) {
				continue;
			}
			double m3 = event.effectiveLitres() / 1000.0;
			if (event.getStatus() == IrrigationStatus.COMPLETED) {
				applied += m3;
				daily.get(date)[0] += m3;
				double[] fieldTotals = perField.computeIfAbsent(event.getField().getId(), (id) -> new double[2]);
				fieldTotals[0] += m3;
				fieldTotals[1]++;
				if (event.getEnergyKwh() != null) {
					double kwh = event.getEnergyKwh().doubleValue();
					energy += kwh;
					Asset pump = (event.getPumpAssetId() != null) ? assetById.get(event.getPumpAssetId()) : null;
					if (pump != null && pump.getPowerSource() != PowerSource.SOLAR
							&& farm.getElectricityTariff() != null) {
						cost += kwh * farm.getElectricityTariff().doubleValue();
						anyCost = true;
					}
				}
			}
			else if (event.getStatus() == IrrigationStatus.PLANNED) {
				planned += m3;
			}
		}

		double requirement = 0;
		double baseline = 0;
		List<FieldWater> fieldRows = new ArrayList<>();
		for (FieldWaterState state : states) {
			double area = (state.field().getAreaHa() != null) ? state.field().getAreaHa().doubleValue() : 1.0;
			double efficiency = state.result().efficiency();
			double fieldRequirement = 0;
			int cropDays = 0;
			for (Day day : state.result().days()) {
				if (day.date().isBefore(from) || day.date().isAfter(to) || day.date().isAfter(today)) {
					continue;
				}
				// Net crop need not met by rain, grossed up for system losses.
				double needMm = Math.max(0, day.etcMm() - day.effectiveRainMm()) / efficiency;
				double needM3 = needMm * area * 10.0;
				fieldRequirement += needM3;
				double[] totals = daily.get(day.date());
				totals[1] += needM3;
				totals[2] += day.precipitationMm();
				totals[3] += day.et0Mm();
				totals[4]++;
				if (day.kc() > 0.21) {
					cropDays++;
				}
			}
			double fieldBaseline = (double) cropDays / farm.getBaselineIntervalDays()
					* farm.getBaselineDepthMm().doubleValue() * area * 10.0;
			double[] appliedTotals = perField.getOrDefault(state.field().getId(), new double[2]);
			requirement += fieldRequirement;
			baseline += fieldBaseline;
			fieldRows.add(new FieldWater(state.field().getId(), state.field().getName(), round(appliedTotals[0]),
					round(fieldRequirement), round(fieldBaseline), round(fieldBaseline - appliedTotals[0]),
					(int) appliedTotals[1]));
		}

		List<DailyWater> dailyRows = daily.entrySet()
			.stream()
			.map((entry) -> {
				double[] t = entry.getValue();
				double fieldsCounted = Math.max(1, t[4]);
				return new DailyWater(entry.getKey(), round(t[0]), round(t[1]), round(t[2] / fieldsCounted),
						round(t[3] / fieldsCounted));
			})
			.toList();

		// Year-to-date draw per licensed source, attributed through the pump that fed each irrigation.
		Map<UUID, Double> usedBySource = new LinkedHashMap<>();
		double unattributed = 0;
		for (IrrigationEvent event : events) {
			if (event.getStatus() != IrrigationStatus.COMPLETED
					|| LocalDate.ofInstant(event.getStartedAt(), zone).isBefore(yearStart)) {
				continue;
			}
			double m3 = event.effectiveLitres() / 1000.0;
			Asset pump = (event.getPumpAssetId() != null) ? assetById.get(event.getPumpAssetId()) : null;
			Asset source = (pump != null && pump.getParentId() != null) ? assetById.get(pump.getParentId()) : null;
			if (source != null && source.getAnnualAllocationM3() != null) {
				usedBySource.merge(source.getId(), m3, Double::sum);
			}
			else {
				unattributed += m3;
			}
		}
		List<SourceAllocation> allocations = assetById.values()
			.stream()
			.filter((asset) -> asset.getAnnualAllocationM3() != null)
			.map((asset) -> {
				double allocation = asset.getAnnualAllocationM3().doubleValue();
				double used = usedBySource.getOrDefault(asset.getId(), 0.0);
				return new SourceAllocation(asset.getId(), asset.getName(), asset.getType(), round(allocation),
						round(used), round(100 * used / allocation));
			})
			.toList();

		String baselineDescription = String.format("A fixed schedule of %s mm every %d days on each planted field",
				farm.getBaselineDepthMm().stripTrailingZeros().toPlainString(), farm.getBaselineIntervalDays());
		return new WaterSummary(from, to, farm.getCurrency(), round(applied), round(planned), round(requirement),
				(requirement > 0) ? round(100 * applied / requirement) : null, round(baseline),
				round(baseline - applied), (baseline > 0) ? round(100 * (baseline - applied) / baseline) : null,
				round(energy), anyCost ? round(cost) : null, baselineDescription, fieldRows, dailyRows, allocations,
				round(unattributed));
	}

	private static double round(double value) {
		return Math.round(value * 10) / 10.0;
	}

}
