package com.irrigaiq.maintenance;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.irrigaiq.assets.Asset;
import com.irrigaiq.assets.AssetService;
import com.irrigaiq.assets.AssetType;
import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.events.FarmDataChanged;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.maintenance.WorkOrder.WorkOrderDetails;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maintenance work orders: from alert or prediction, through the workshop, back to a healthy asset. */
@Service
public class WorkOrderService {

	private final WorkOrderRepository workOrders;

	private final FarmService farms;

	private final AssetService assets;

	private final AuditService audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public WorkOrderService(WorkOrderRepository workOrders, FarmService farms, AssetService assets, AuditService audit,
			ApplicationEventPublisher events, Clock clock) {
		this.workOrders = workOrders;
		this.farms = farms;
		this.assets = assets;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	public record AssetRef(UUID id, String name, AssetType type) {
	}

	public record WorkOrderView(UUID id, Long number, String reference, UUID farmId, AssetRef asset, UUID alertId,
			WorkOrderKind kind, WorkOrderPriority priority, WorkOrderStatus status, String title, String description,
			LocalDate dueOn, String assignee, UUID createdBy, Instant startedAt, Instant completedAt,
			String completionNotes, BigDecimal cost, BigDecimal downtimeHours, boolean overdue, Instant createdAt,
			Instant updatedAt) {
	}

	/**
	 * @param resetServiceInterval restart the asset's service clock (defaults to true for preventive work)
	 * @param restoreOperational mark a degraded/down asset operational again
	 */
	public record Completion(String notes, BigDecimal cost, BigDecimal downtimeHours, Boolean resetServiceInterval,
			boolean restoreOperational) {
	}

	@Transactional(readOnly = true)
	public List<WorkOrderView> list(UUID farmId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		return workOrders.findByFarmIdOrderByCreatedAtDesc(farmId).stream().map((w) -> view(w, farm)).toList();
	}

	@Transactional(readOnly = true)
	public WorkOrderView get(UUID farmId, UUID workOrderId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		return view(require(farmId, workOrderId), farm);
	}

	@Transactional
	public WorkOrderView create(UUID farmId, AuthenticatedUser user, UUID assetId, WorkOrderDetails details) {
		Farm farm = farms.requireAccessible(farmId, user);
		Asset asset = (assetId != null) ? assets.requireInFarm(farmId, assetId) : null;
		WorkOrder workOrder = workOrders.saveAndFlush(new WorkOrder(farm, asset, null, details, user.id()));
		events.publishEvent(new FarmDataChanged(farmId));
		return view(workOrder, farm);
	}

	/** Module API: raise a work order from an alert (farm already authorised by the caller). */
	@Transactional
	public WorkOrder createFromAlert(Farm farm, Asset asset, UUID alertId, WorkOrderDetails details, UUID createdBy) {
		WorkOrder workOrder = workOrders.saveAndFlush(new WorkOrder(farm, asset, alertId, details, createdBy));
		events.publishEvent(new FarmDataChanged(farm.getId()));
		return workOrder;
	}

	@Transactional
	public WorkOrderView update(UUID farmId, UUID workOrderId, AuthenticatedUser user, UUID assetId,
			WorkOrderDetails details) {
		Farm farm = farms.requireAccessible(farmId, user);
		WorkOrder workOrder = require(farmId, workOrderId);
		Asset asset = (assetId != null) ? assets.requireInFarm(farmId, assetId) : null;
		workOrder.edit(asset, details);
		workOrders.flush();
		events.publishEvent(new FarmDataChanged(farmId));
		return view(workOrder, farm);
	}

	@Transactional
	public WorkOrderView start(UUID farmId, UUID workOrderId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		WorkOrder workOrder = require(farmId, workOrderId);
		workOrder.start(clock.instant());
		workOrders.flush();
		return view(workOrder, farm);
	}

	@Transactional
	public WorkOrderView complete(UUID farmId, UUID workOrderId, AuthenticatedUser user, Completion completion) {
		Farm farm = farms.requireAccessible(farmId, user);
		WorkOrder workOrder = require(farmId, workOrderId);
		workOrder.complete(completion.notes(), completion.cost(), completion.downtimeHours(), clock.instant());
		workOrders.flush();
		if (workOrder.getAsset() != null) {
			boolean resetService = (completion.resetServiceInterval() != null) ? completion.resetServiceInterval()
					: workOrder.getKind() == WorkOrderKind.PREVENTIVE;
			assets.recordService(workOrder.getAsset().getId(), LocalDate.now(clock.withZone(farm.zone())), resetService,
					completion.restoreOperational());
		}
		audit.record(AuditEntry.of(AuditAction.WORK_ORDER_COMPLETED)
			.target("WORK_ORDER", workOrder.getId())
			.detail("reference", reference(workOrder))
			.detail("cost", completion.cost()));
		events.publishEvent(new FarmDataChanged(farmId));
		return view(workOrder, farm);
	}

	@Transactional
	public WorkOrderView cancel(UUID farmId, UUID workOrderId, AuthenticatedUser user, String reason) {
		Farm farm = farms.requireAccessible(farmId, user);
		WorkOrder workOrder = require(farmId, workOrderId);
		workOrder.cancel(reason);
		workOrders.flush();
		events.publishEvent(new FarmDataChanged(farmId));
		return view(workOrder, farm);
	}

	@Transactional
	public WorkOrderView reopen(UUID farmId, UUID workOrderId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		WorkOrder workOrder = require(farmId, workOrderId);
		workOrder.reopen();
		workOrders.flush();
		events.publishEvent(new FarmDataChanged(farmId));
		return view(workOrder, farm);
	}

	/** Module API for dashboards and the briefing. */
	@Transactional(readOnly = true)
	public List<WorkOrderView> forFarm(Farm farm) {
		return workOrders.findByFarmIdOrderByCreatedAtDesc(farm.getId()).stream().map((w) -> view(w, farm)).toList();
	}

	private WorkOrder require(UUID farmId, UUID workOrderId) {
		return workOrders.findByIdAndFarmId(workOrderId, farmId).orElseThrow(() -> ApiException.notFound("Work order"));
	}

	private WorkOrderView view(WorkOrder w, Farm farm) {
		LocalDate today = LocalDate.now(clock.withZone(farm.zone()));
		Asset asset = w.getAsset();
		return new WorkOrderView(w.getId(), w.getNumber(), reference(w), farm.getId(),
				(asset != null) ? new AssetRef(asset.getId(), asset.getName(), asset.getType()) : null, w.getAlertId(),
				w.getKind(), w.getPriority(), w.getStatus(), w.getTitle(), w.getDescription(), w.getDueOn(),
				w.getAssignee(), w.getCreatedBy(), w.getStartedAt(), w.getCompletedAt(), w.getCompletionNotes(),
				w.getCost(), w.getDowntimeHours(),
				w.getStatus().isActive() && w.getDueOn() != null && w.getDueOn().isBefore(today), w.getCreatedAt(),
				w.getUpdatedAt());
	}

	static String reference(WorkOrder workOrder) {
		return (workOrder.getNumber() != null) ? "WO-" + workOrder.getNumber() : null;
	}

}
