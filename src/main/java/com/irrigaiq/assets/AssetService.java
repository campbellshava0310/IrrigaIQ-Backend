package com.irrigaiq.assets;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.irrigaiq.assets.AssetConditionCheck.CheckDetails;
import com.irrigaiq.assets.AssetHealthEngine.CheckInput;
import com.irrigaiq.assets.AssetHealthEngine.Health;
import com.irrigaiq.assets.AssetViews.AssetDetailView;
import com.irrigaiq.assets.AssetViews.AssetHealthSnapshot;
import com.irrigaiq.assets.AssetViews.AssetView;
import com.irrigaiq.assets.AssetViews.ConditionCheckView;
import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.common.events.FarmDataChanged;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.farm.FieldRepository;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Water infrastructure registry, inspections, and rule-based health/service prediction. */
@Service
public class AssetService {

	static final Duration HEALTH_WINDOW = Duration.ofDays(120);

	static final int USAGE_WINDOW_DAYS = 30;

	private final AssetRepository assets;

	private final ConditionCheckRepository checks;

	private final FarmService farms;

	private final FieldRepository fields;

	private final PumpUsageProvider usage;

	private final OpenWorkOrderCounter openWork;

	private final AuditService audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public AssetService(AssetRepository assets, ConditionCheckRepository checks, FarmService farms,
			FieldRepository fields, PumpUsageProvider usage, OpenWorkOrderCounter openWork, AuditService audit,
			ApplicationEventPublisher events, Clock clock) {
		this.assets = assets;
		this.checks = checks;
		this.farms = farms;
		this.fields = fields;
		this.usage = usage;
		this.openWork = openWork;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<AssetView> list(UUID farmId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		return assess(farm).stream().map((s) -> AssetView.from(s.asset(), s.health())).toList();
	}

	@Transactional(readOnly = true)
	public AssetDetailView get(UUID farmId, UUID assetId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		Map<UUID, AssetHealthSnapshot> byId = assess(farm).stream()
			.collect(Collectors.toMap((s) -> s.asset().getId(), (s) -> s));
		AssetHealthSnapshot snapshot = byId.get(assetId);
		if (snapshot == null) {
			throw ApiException.notFound("Asset");
		}
		List<ConditionCheckView> recent = checks.findByAssetIdOrderByCheckedAtDesc(assetId, PageRequest.of(0, 30))
			.stream()
			.map(ConditionCheckView::from)
			.toList();
		List<AssetView> children = byId.values()
			.stream()
			.filter((s) -> assetId.equals(s.asset().getParentId()))
			.map((s) -> AssetView.from(s.asset(), s.health()))
			.toList();
		UUID parentId = snapshot.asset().getParentId();
		AssetView parent = (parentId != null && byId.containsKey(parentId))
				? AssetView.from(byId.get(parentId).asset(), byId.get(parentId).health()) : null;
		return new AssetDetailView(AssetView.from(snapshot.asset(), snapshot.health()), recent, children, parent);
	}

	@Transactional
	public AssetView create(UUID farmId, AuthenticatedUser user, AssetDetails details) {
		Farm farm = farms.requireAccessible(farmId, user);
		validate(farm, details, null);
		Asset asset = assets.save(new Asset(farm, details));
		audit.record(AuditEntry.of(AuditAction.ASSET_CREATED)
			.target("ASSET", asset.getId())
			.detail("name", asset.getName())
			.detail("type", asset.getType()));
		events.publishEvent(new FarmDataChanged(farmId));
		return AssetView.from(asset, healthOf(farm, asset));
	}

	@Transactional
	public AssetView update(UUID farmId, UUID assetId, AuthenticatedUser user, AssetDetails details) {
		Farm farm = farms.requireAccessible(farmId, user);
		Asset asset = requireInFarm(farmId, assetId);
		validate(farm, details, assetId);
		asset.update(details);
		assets.flush();
		events.publishEvent(new FarmDataChanged(farmId));
		return AssetView.from(asset, healthOf(farm, asset));
	}

	@Transactional
	public AssetView changeStatus(UUID farmId, UUID assetId, AuthenticatedUser user, AssetStatus status) {
		Farm farm = farms.requireAccessible(farmId, user);
		Asset asset = requireInFarm(farmId, assetId);
		AssetStatus previous = asset.getStatus();
		asset.changeStatus(status);
		assets.flush();
		audit.record(AuditEntry.of(AuditAction.ASSET_STATUS_CHANGED)
			.target("ASSET", assetId)
			.detail("from", previous)
			.detail("to", status));
		events.publishEvent(new FarmDataChanged(farmId));
		return AssetView.from(asset, healthOf(farm, asset));
	}

	@Transactional
	public void delete(UUID farmId, UUID assetId, AuthenticatedUser user) {
		farms.requireAccessible(farmId, user);
		Asset asset = requireInFarm(farmId, assetId);
		assets.delete(asset);
		audit.record(AuditEntry.of(AuditAction.ASSET_DELETED).target("ASSET", assetId).detail("name", asset.getName()));
		events.publishEvent(new FarmDataChanged(farmId));
	}

	@Transactional
	public ConditionCheckView recordCheck(UUID farmId, UUID assetId, AuthenticatedUser user, CheckDetails details) {
		farms.requireAccessible(farmId, user);
		Asset asset = requireInFarm(farmId, assetId);
		Instant now = clock.instant();
		if (details.checkedAt() != null && details.checkedAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "A condition check cannot be in the future");
		}
		AssetConditionCheck check = checks.save(new AssetConditionCheck(asset, details, user.id(), now));
		asset.observeHourMeter(details.hourMeter());
		events.publishEvent(new FarmDataChanged(farmId));
		return ConditionCheckView.from(check);
	}

	@Transactional(readOnly = true)
	public List<ConditionCheckView> checks(UUID farmId, UUID assetId, AuthenticatedUser user, int limit) {
		farms.requireAccessible(farmId, user);
		requireInFarm(farmId, assetId);
		return checks.findByAssetIdOrderByCheckedAtDesc(assetId, PageRequest.of(0, limit))
			.stream()
			.map(ConditionCheckView::from)
			.toList();
	}

	// ---- Module API (callers have already authorised the farm) ----

	/** Every asset of the farm with its current health, in one batch of queries. */
	@Transactional(readOnly = true)
	public List<AssetHealthSnapshot> assess(Farm farm) {
		List<Asset> farmAssets = assets.findByFarmIdOrderByNameAsc(farm.getId());
		if (farmAssets.isEmpty()) {
			return List.of();
		}
		LocalDate today = LocalDate.now(clock.withZone(farm.zone()));
		Map<UUID, List<CheckInput>> recentChecks = checks.findByFarmSince(farm.getId(), clock.instant().minus(HEALTH_WINDOW))
			.stream()
			.collect(Collectors.groupingBy((check) -> check.getAsset().getId(),
					Collectors.mapping((check) -> toInput(check, farm), Collectors.toList())));
		Map<UUID, Integer> open = openWork.openCorrectiveWorkOrders(farm.getId());
		Map<UUID, Double> runHours = usage.runHoursSince(farm.getId(),
				clock.instant().minus(Duration.ofDays(USAGE_WINDOW_DAYS)));

		List<AssetHealthSnapshot> snapshots = new ArrayList<>(farmAssets.size());
		for (Asset asset : farmAssets) {
			Health health = AssetHealthEngine.assess(input(asset, today, recentChecks.getOrDefault(asset.getId(), List.of()),
					open.getOrDefault(asset.getId(), 0), runHours.getOrDefault(asset.getId(), 0.0) / USAGE_WINDOW_DAYS));
			snapshots.add(new AssetHealthSnapshot(asset, health));
		}
		return snapshots;
	}

	/** Loads an asset of the given farm, or 404. */
	public Asset requireInFarm(UUID farmId, UUID assetId) {
		return assets.findByIdAndFarmId(assetId, farmId).orElseThrow(() -> ApiException.notFound("Asset"));
	}

	@Transactional
	public void addRunHours(UUID assetId, double hours) {
		assets.findById(assetId).ifPresent((asset) -> asset.addRunHours(hours));
	}

	@Transactional
	public void removeRunHours(UUID assetId, double hours) {
		assets.findById(assetId).ifPresent((asset) -> asset.removeRunHours(hours));
	}

	/** Records a completed service; optionally returns a degraded or down asset to operation. */
	@Transactional
	public void recordService(UUID assetId, LocalDate on, boolean resetServiceInterval, boolean restoreOperational) {
		assets.findById(assetId).ifPresent((asset) -> {
			if (resetServiceInterval) {
				asset.recordService(on);
			}
			if (restoreOperational && asset.getStatus() != AssetStatus.RETIRED) {
				asset.changeStatus(AssetStatus.OPERATIONAL);
			}
		});
	}

	private Health healthOf(Farm farm, Asset asset) {
		return assess(farm).stream()
			.filter((s) -> s.asset().getId().equals(asset.getId()))
			.map(AssetHealthSnapshot::health)
			.findFirst()
			.orElse(null);
	}

	private void validate(Farm farm, AssetDetails details, UUID selfId) {
		if (details.fieldId() != null && fields.findByIdAndFarmId(details.fieldId(), farm.getId()).isEmpty()) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "The field does not belong to this farm");
		}
		if (details.parentId() != null) {
			UUID cursor = details.parentId();
			for (int depth = 0; cursor != null; depth++) {
				if (cursor.equals(selfId) || depth > 20) {
					throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
							"An asset cannot be connected downstream of itself");
				}
				Asset parent = assets.findByIdAndFarmId(cursor, farm.getId())
					.orElseThrow(() -> ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
							"The upstream asset does not belong to this farm"));
				cursor = parent.getParentId();
			}
		}
		boolean hasStart = details.operatingWindowStart() != null;
		boolean hasEnd = details.operatingWindowEnd() != null;
		if (hasStart != hasEnd || (hasStart && Objects.equals(details.operatingWindowStart(), details.operatingWindowEnd()))) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
					"The operating window needs different start and end times");
		}
	}

	private static CheckInput toInput(AssetConditionCheck check, Farm farm) {
		return new CheckInput(LocalDate.ofInstant(check.getCheckedAt(), farm.zone()), check.getOverallCondition(),
				check.getVibration(), check.isLeakDetected(), toDouble(check.getFilterDpBar()),
				toDouble(check.getPowerKw()), toDouble(check.getFlowM3h()), toDouble(check.getWaterLevelM()));
	}

	private static AssetHealthEngine.Input input(Asset asset, LocalDate today, List<CheckInput> recentChecks,
			int openCorrective, double runHoursPerDay) {
		return new AssetHealthEngine.Input(today, asset.getType(), asset.getStatus(), asset.getInstalledOn(),
				asset.getExpectedLifeYears(), asset.getServiceIntervalDays(), asset.getServiceIntervalHours(),
				asset.getOperatingHours().doubleValue(), asset.getHoursAtLastService().doubleValue(),
				asset.getLastServiceOn(), toDouble(asset.getBaselineEnergyKwhPerM3()), toDouble(asset.getRatedPowerKw()),
				toDouble(asset.getRatedFlowM3h()), recentChecks, openCorrective, runHoursPerDay);
	}

	private static Double toDouble(java.math.BigDecimal value) {
		return (value != null) ? value.doubleValue() : null;
	}

}
