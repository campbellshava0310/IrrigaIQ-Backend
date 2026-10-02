package com.irrigaiq.ml;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.irrigaiq.assets.Asset;
import com.irrigaiq.assets.AssetConditionCheck;
import com.irrigaiq.assets.AssetHealthEngine.Health;
import com.irrigaiq.assets.AssetService;
import com.irrigaiq.assets.AssetStatus;
import com.irrigaiq.assets.AssetType;
import com.irrigaiq.assets.AssetViews.AssetHealthSnapshot;
import com.irrigaiq.assets.RiskLevel;
import com.irrigaiq.assets.VibrationLevel;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.ml.MlApi.AssetFeatures;
import com.irrigaiq.ml.MlApi.EquipmentRisk;
import com.irrigaiq.ml.MlApi.PumpReadings;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.telemetry.DeviceService;
import com.irrigaiq.telemetry.DeviceStatus;
import com.irrigaiq.telemetry.ReadingStore;
import com.irrigaiq.telemetry.ReadingStore.Window;
import com.irrigaiq.telemetry.Sensor;
import com.irrigaiq.telemetry.SensorType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pump failure risk from the ML model, beside the rule-based health score. Features come from a
 * pump monitor when one is fitted (7-day means), otherwise from the latest condition check and
 * the asset's service and age records. The model tolerates missing inputs and reports which were
 * missing, so an uninstrumented pump gets a less informed, clearly labelled estimate.
 */
@Service
public class EquipmentRiskService {

	static final Duration WINDOW = Duration.ofDays(7);

	/** Condition checks older than this say little about today's vibration or energy use. */
	static final Duration CHECK_RELEVANCE = Duration.ofDays(60);

	/** Metres of head per bar. */
	static final double METRES_PER_BAR = 10.2;

	private final FarmService farms;

	private final AssetService assets;

	private final DeviceService devices;

	private final ReadingStore readings;

	private final MlClient ml;

	private final Clock clock;

	public EquipmentRiskService(FarmService farms, AssetService assets, DeviceService devices, ReadingStore readings, MlClient ml, Clock clock) {
		this.farms = farms;
		this.assets = assets;
		this.devices = devices;
		this.readings = readings;
		this.ml = ml;
		this.clock = clock;
	}

	/**
	 * @param sources where the model's inputs came from ({@code PUMP_MONITOR}, {@code CONDITION_CHECK},
	 * {@code SERVICE_RECORDS})
	 */
	public record PumpRisk(UUID assetId, String assetName, AssetStatus assetStatus, String status, EquipmentRisk risk,
			PumpReadings inputs, List<String> sources, int ruleHealthScore, RiskLevel ruleRisk) {
	}

	public record EquipmentRiskView(boolean available, Instant assessedAt, List<PumpRisk> pumps) {
	}

	@Transactional(readOnly = true)
	public EquipmentRiskView assess(UUID farmId, AuthenticatedUser user) {
		return assess(farms.requireAccessible(farmId, user));
	}

	@Transactional(readOnly = true)
	public EquipmentRiskView assess(Farm farm) {
		List<AssetHealthSnapshot> pumps = assets.assess(farm)
			.stream()
			.filter((s) -> s.asset().getType() == AssetType.PUMP && s.asset().getStatus() != AssetStatus.RETIRED)
			.toList();
		Map<UUID, Sensor> monitors = devices.devices(farm, SensorType.PUMP_MONITOR)
			.stream()
			.filter((m) -> m.getStatus() == DeviceStatus.ACTIVE && m.getAssetId() != null)
			.collect(Collectors.toMap(Sensor::getAssetId, Function.identity(), (a, b) -> a));
		LocalDate today = LocalDate.now(clock.withZone(farm.zone()));

		Map<UUID, PumpReadings> inputs = new LinkedHashMap<>();
		Map<UUID, List<String>> sources = new LinkedHashMap<>();
		for (AssetHealthSnapshot snapshot : pumps) {
			List<String> used = new ArrayList<>();
			inputs.put(snapshot.asset().getId(), features(snapshot, monitors.get(snapshot.asset().getId()), today, used));
			sources.put(snapshot.asset().getId(), used);
		}
		Optional<List<EquipmentRisk>> risks = ml.equipmentRisk(inputs.entrySet()
			.stream()
			.map((e) -> new AssetFeatures(e.getKey().toString(), e.getValue()))
			.toList());
		Map<String, EquipmentRisk> byId = risks.orElse(List.of())
			.stream()
			.collect(Collectors.toMap(EquipmentRisk::assetId, Function.identity()));

		List<PumpRisk> out = pumps.stream().map((s) -> {
			Asset asset = s.asset();
			EquipmentRisk risk = byId.get(asset.getId().toString());
			return new PumpRisk(asset.getId(), asset.getName(), asset.getStatus(), (risk != null) ? "OK" : "UNAVAILABLE",
					risk, inputs.get(asset.getId()), sources.get(asset.getId()), s.health().score(), s.health().risk());
		}).toList();
		return new EquipmentRiskView(risks.isPresent(), clock.instant(), out);
	}

	private PumpReadings features(AssetHealthSnapshot snapshot, Sensor monitor, LocalDate today, List<String> used) {
		Asset asset = snapshot.asset();
		Health health = snapshot.health();
		Double baselineKwhPerM3 = baseline(asset);
		Double ratedFlow = decimal(asset.getRatedFlowM3h());

		Double energyRatio = null;
		Double energyTrend = null;
		Double flowRatio = null;
		Double pressureRatio = null;
		Double vibration = null;
		Double motorTemp = null;

		if (monitor != null) {
			Instant now = clock.instant();
			Window recent = readings.window(monitor.getId(), now.minus(WINDOW), now);
			if (recent.samples() > 0) {
				used.add("PUMP_MONITOR");
				energyRatio = ratio(recent.kwhPerM3(), baselineKwhPerM3);
				Window earlier = readings.window(monitor.getId(), now.minus(WINDOW.multipliedBy(3)),
						now.minus(WINDOW.multipliedBy(2)));
				Double earlierRatio = ratio(earlier.kwhPerM3(), baselineKwhPerM3);
				energyTrend = (energyRatio != null && earlierRatio != null) ? energyRatio - earlierRatio : null;
				flowRatio = (recent.flowLpm() != null) ? ratio(recent.flowLpm() * 0.06, ratedFlow) : null;
				Double ratedHead = decimal(asset.getRatedHeadM());
				pressureRatio = (recent.pressureBar() != null) ? ratio(recent.pressureBar() * METRES_PER_BAR, ratedHead)
						: null;
				vibration = recent.vibrationMmS();
				motorTemp = recent.motorTempC();
			}
		}

		AssetConditionCheck check = assets.latestCheck(asset.getId())
			.filter((c) -> c.getCheckedAt().isAfter(clock.instant().minus(CHECK_RELEVANCE)))
			.orElse(null);
		if (check != null) {
			boolean contributed = false;
			if (energyRatio == null && health.energyDriftPct() != null) {
				energyRatio = 1 + health.energyDriftPct() / 100;
				contributed = true;
			}
			if (flowRatio == null && check.getFlowM3h() != null) {
				flowRatio = ratio(check.getFlowM3h().doubleValue(), ratedFlow);
				contributed |= flowRatio != null;
			}
			if (vibration == null && check.getVibration() != null) {
				vibration = vibrationMmS(check.getVibration());
				contributed = true;
			}
			if (contributed) {
				used.add("CONDITION_CHECK");
			}
		}

		Double overdue = (health.service() != null) ? health.service().progressPct() / 100 : null;
		Double age = (asset.getInstalledOn() != null && asset.getExpectedLifeYears() != null
				&& asset.getExpectedLifeYears() > 0)
						? ChronoUnit.DAYS.between(asset.getInstalledOn(), today) / 365.25 / asset.getExpectedLifeYears()
						: null;
		Double runtime = (snapshot.runHoursPerDay() > 0) ? snapshot.runHoursPerDay() : null;
		if (overdue != null || age != null || runtime != null) {
			used.add("SERVICE_RECORDS");
		}
		return new PumpReadings(round(energyRatio), round(energyTrend), round(flowRatio), round(pressureRatio),
				round(vibration), round(motorTemp), round(runtime), null, round(overdue), round(age));
	}

	/**
	 * Midpoints of the ISO 10816-3 vibration zones for a field assessment by touch and sound:
	 * normal = zone A/B, elevated = zone C (4.5-7.1 mm/s), severe = zone D (above 7.1 mm/s).
	 */
	static double vibrationMmS(VibrationLevel level) {
		return switch (level) {
			case NORMAL -> 2.0;
			case ELEVATED -> 5.8;
			case SEVERE -> 9.0;
		};
	}

	private static Double baseline(Asset asset) {
		if (asset.getBaselineEnergyKwhPerM3() != null) {
			return asset.getBaselineEnergyKwhPerM3().doubleValue();
		}
		if (asset.getRatedPowerKw() != null && asset.getRatedFlowM3h() != null
				&& asset.getRatedFlowM3h().signum() > 0) {
			return asset.getRatedPowerKw().doubleValue() / asset.getRatedFlowM3h().doubleValue();
		}
		return null;
	}

	private static Double ratio(Double value, Double reference) {
		return (value != null && reference != null && reference > 0) ? value / reference : null;
	}

	private static Double decimal(java.math.BigDecimal value) {
		return (value != null) ? value.doubleValue() : null;
	}

	private static Double round(Double value) {
		return (value != null) ? Math.round(value * 1000) / 1000.0 : null;
	}

}
