package com.irrigaiq.maintenance.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.maintenance.WorkOrder.WorkOrderDetails;
import com.irrigaiq.maintenance.WorkOrderKind;
import com.irrigaiq.maintenance.WorkOrderPriority;
import com.irrigaiq.maintenance.WorkOrderService;
import com.irrigaiq.maintenance.WorkOrderService.Completion;
import com.irrigaiq.maintenance.WorkOrderService.WorkOrderView;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/farms/{farmId}/work-orders")
@Tag(name = "Maintenance")
public class WorkOrderController {

	private final WorkOrderService workOrders;

	public WorkOrderController(WorkOrderService workOrders) {
		this.workOrders = workOrders;
	}

	public record WorkOrderRequest(UUID assetId, @NotNull WorkOrderKind kind, WorkOrderPriority priority,
			@NotBlank @Size(max = 200) String title, @Size(max = 2000) String description, LocalDate dueOn,
			@Size(max = 160) String assignee) {

		WorkOrderDetails toDetails() {
			return new WorkOrderDetails(kind, priority, title, description, dueOn, assignee);
		}

	}

	public record CompleteRequest(@Size(max = 2000) String notes,
			@PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal cost,
			@PositiveOrZero @Digits(integer = 6, fraction = 2) BigDecimal downtimeHours, Boolean resetServiceInterval,
			boolean restoreOperational) {
	}

	public record CancelRequest(@Size(max = 2000) String reason) {
	}

	@GetMapping
	public List<WorkOrderView> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return workOrders.list(farmId, user);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public WorkOrderView create(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@Valid @RequestBody WorkOrderRequest body) {
		return workOrders.create(farmId, user, body.assetId(), body.toDetails());
	}

	@GetMapping("/{workOrderId}")
	public WorkOrderView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID workOrderId) {
		return workOrders.get(farmId, workOrderId, user);
	}

	@PutMapping("/{workOrderId}")
	public WorkOrderView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID workOrderId, @Valid @RequestBody WorkOrderRequest body) {
		return workOrders.update(farmId, workOrderId, user, body.assetId(), body.toDetails());
	}

	@PostMapping("/{workOrderId}/start")
	public WorkOrderView start(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID workOrderId) {
		return workOrders.start(farmId, workOrderId, user);
	}

	@PostMapping("/{workOrderId}/complete")
	@Operation(summary = "Close a work order; preventive work restarts the asset's service interval")
	public WorkOrderView complete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID workOrderId, @Valid @RequestBody CompleteRequest body) {
		return workOrders.complete(farmId, workOrderId, user, new Completion(body.notes(), body.cost(),
				body.downtimeHours(), body.resetServiceInterval(), body.restoreOperational()));
	}

	@PostMapping("/{workOrderId}/cancel")
	public WorkOrderView cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID workOrderId, @Valid @RequestBody(required = false) CancelRequest body) {
		return workOrders.cancel(farmId, workOrderId, user, (body != null) ? body.reason() : null);
	}

	@PostMapping("/{workOrderId}/reopen")
	public WorkOrderView reopen(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID workOrderId) {
		return workOrders.reopen(farmId, workOrderId, user);
	}

}
