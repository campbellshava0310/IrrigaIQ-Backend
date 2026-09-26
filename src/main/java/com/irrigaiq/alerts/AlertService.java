package com.irrigaiq.alerts;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.irrigaiq.alerts.AlertEvaluator.Outcome;
import com.irrigaiq.assets.Asset;
import com.irrigaiq.assets.AssetRepository;
import com.irrigaiq.assets.AssetService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.farm.Field;
import com.irrigaiq.farm.FieldRepository;
import com.irrigaiq.maintenance.WorkOrder;
import com.irrigaiq.maintenance.WorkOrder.WorkOrderDetails;
import com.irrigaiq.maintenance.WorkOrderKind;
import com.irrigaiq.maintenance.WorkOrderPriority;
import com.irrigaiq.maintenance.WorkOrderService;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The alert inbox: triage, acknowledgement, resolution and hand-off to maintenance. */
@Service
public class AlertService {

	private final AlertRepository alerts;

	private final AlertEvaluator evaluator;

	private final FarmService farms;

	private final FieldRepository fields;

	private final AssetRepository assets;

	private final AssetService assetService;

	private final WorkOrderService workOrders;

	private final Clock clock;

	public AlertService(AlertRepository alerts, AlertEvaluator evaluator, FarmService farms, FieldRepository fields,
			AssetRepository assets, AssetService assetService, WorkOrderService workOrders, Clock clock) {
		this.alerts = alerts;
		this.evaluator = evaluator;
		this.farms = farms;
		this.fields = fields;
		this.assets = assets;
		this.assetService = assetService;
		this.workOrders = workOrders;
		this.clock = clock;
	}

	public record AlertView(UUID id, AlertType type, AlertSeverity severity, AlertState state, String title,
			String message, UUID fieldId, String fieldName, UUID assetId, String assetName, Map<String, Object> details,
			String source, UUID workOrderId, Instant createdAt, Instant updatedAt, Instant acknowledgedAt,
			Instant resolvedAt, boolean autoResolved) {
	}

	public record AlertSummary(int open, int acknowledged, int critical, int warning, int info) {
	}

	/** @param includeResolved also return the most recently resolved alerts (history) */
	@Transactional(readOnly = true)
	public List<AlertView> list(UUID farmId, AuthenticatedUser user, boolean includeResolved) {
		Farm farm = farms.requireAccessible(farmId, user);
		Stream<Alert> active = alerts.findByFarmIdAndResolvedAtIsNull(farmId)
			.stream()
			.sorted(Comparator.comparing((Alert a) -> a.getSeverity().ordinal())
				.reversed()
				.thenComparing(Alert::getCreatedAt, Comparator.reverseOrder()));
		Stream<Alert> all = includeResolved
				? Stream.concat(active,
						alerts.findByFarmIdAndResolvedAtIsNotNullOrderByResolvedAtDesc(farmId, PageRequest.of(0, 50))
							.stream())
				: active;
		Names names = names(farm);
		return all.map((alert) -> view(alert, names)).toList();
	}

	@Transactional(readOnly = true)
	public AlertSummary summary(Farm farm) {
		List<Alert> active = alerts.findByFarmIdAndResolvedAtIsNull(farm.getId());
		return new AlertSummary((int) active.stream().filter((a) -> a.state() == AlertState.OPEN).count(),
				(int) active.stream().filter((a) -> a.state() == AlertState.ACKNOWLEDGED).count(),
				count(active, AlertSeverity.CRITICAL), count(active, AlertSeverity.WARNING),
				count(active, AlertSeverity.INFO));
	}

	@Transactional
	public AlertView acknowledge(UUID farmId, UUID alertId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		Alert alert = require(farmId, alertId);
		alert.acknowledge(user.id(), clock.instant());
		return view(alert, names(farm));
	}

	@Transactional
	public AlertView resolve(UUID farmId, UUID alertId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		Alert alert = require(farmId, alertId);
		alert.resolve(user.id(), clock.instant());
		return view(alert, names(farm));
	}

	public record WorkOrderLink(AlertView alert, UUID workOrderId, String reference) {
	}

	/** Raises a work order from an infrastructure alert and acknowledges the alert. */
	@Transactional
	public WorkOrderLink createWorkOrder(UUID farmId, UUID alertId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		Alert alert = require(farmId, alertId);
		if (alert.getWorkOrderId() != null) {
			throw ApiException.conflict(ErrorCode.CONFLICT, "A work order already exists for this alert");
		}
		Asset asset = (alert.getAssetId() != null) ? assetService.requireInFarm(farmId, alert.getAssetId()) : null;
		WorkOrderKind kind = switch (alert.getType()) {
			case ASSET_DOWN, LEAK_REPORTED -> WorkOrderKind.CORRECTIVE;
			case SERVICE_OVERDUE -> WorkOrderKind.PREVENTIVE;
			case FILTER_CLOGGING, PUMP_EFFICIENCY_DROP, ASSET_FAILURE_RISK, WATER_LEVEL_FALLING -> WorkOrderKind.PREDICTIVE;
			default -> WorkOrderKind.INSPECTION;
		};
		WorkOrderPriority priority = switch (alert.getSeverity()) {
			case CRITICAL -> WorkOrderPriority.URGENT;
			case WARNING -> WorkOrderPriority.HIGH;
			case INFO -> WorkOrderPriority.MEDIUM;
		};
		Object action = (alert.getDetails() != null) ? alert.getDetails().get("recommendedAction") : null;
		String description = alert.getMessage() + ((action != null && !alert.getMessage().contains(action.toString()))
				? "\n\nRecommended: " + action : "");
		WorkOrder workOrder = workOrders.createFromAlert(farm, asset, alert.getId(),
				new WorkOrderDetails(kind, priority, alert.getTitle(), description, null, null), user.id());
		Instant now = clock.instant();
		alert.linkWorkOrder(workOrder.getId(), now);
		alert.acknowledge(user.id(), now);
		return new WorkOrderLink(view(alert, names(farm)), workOrder.getId(), "WO-" + workOrder.getNumber());
	}

	@Transactional
	public Outcome evaluateNow(UUID farmId, AuthenticatedUser user) {
		return evaluator.evaluate(farms.requireAccessible(farmId, user));
	}

	private Alert require(UUID farmId, UUID alertId) {
		return alerts.findByIdAndFarmId(alertId, farmId).orElseThrow(() -> ApiException.notFound("Alert"));
	}

	private record Names(Map<UUID, String> fields, Map<UUID, String> assets) {
	}

	private Names names(Farm farm) {
		return new Names(
				fields.findByFarmIdOrderByNameAsc(farm.getId()).stream().collect(Collectors.toMap(Field::getId, Field::getName)),
				assets.findByFarmIdOrderByNameAsc(farm.getId()).stream().collect(Collectors.toMap(Asset::getId, Asset::getName)));
	}

	private static AlertView view(Alert alert, Names names) {
		boolean autoResolved = alert.getResolvedAt() != null && alert.getResolvedBy() == null;
		return new AlertView(alert.getId(), alert.getType(), alert.getSeverity(), alert.state(), alert.getTitle(),
				alert.getMessage(), alert.getFieldId(),
				(alert.getFieldId() != null) ? names.fields().get(alert.getFieldId()) : null, alert.getAssetId(),
				(alert.getAssetId() != null) ? names.assets().get(alert.getAssetId()) : null, alert.getDetails(),
				alert.getSource(), alert.getWorkOrderId(), alert.getCreatedAt(), alert.getUpdatedAt(),
				alert.getAcknowledgedAt(), alert.getResolvedAt(), autoResolved);
	}

	private static int count(List<Alert> active, AlertSeverity severity) {
		return (int) active.stream().filter((a) -> a.getSeverity() == severity).count();
	}

}
