package com.irrigaiq.alerts.web;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.alerts.AlertEvaluator.Outcome;
import com.irrigaiq.alerts.AlertService;
import com.irrigaiq.alerts.AlertService.AlertView;
import com.irrigaiq.alerts.AlertService.WorkOrderLink;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/farms/{farmId}/alerts")
@Tag(name = "Alerts")
public class AlertController {

	private final AlertService alerts;

	public AlertController(AlertService alerts) {
		this.alerts = alerts;
	}

	@GetMapping
	@Operation(summary = "Active alerts (most severe first), optionally with recent history")
	public List<AlertView> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@RequestParam(defaultValue = "false") boolean includeResolved) {
		return alerts.list(farmId, user, includeResolved);
	}

	@PostMapping("/evaluate")
	@Operation(summary = "Re-check all alert rules for the farm now")
	public Outcome evaluate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return alerts.evaluateNow(farmId, user);
	}

	@PostMapping("/{alertId}/acknowledge")
	public AlertView acknowledge(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID alertId) {
		return alerts.acknowledge(farmId, alertId, user);
	}

	@PostMapping("/{alertId}/resolve")
	@Operation(summary = "Resolve an alert; rule alerts are not re-raised for 24 hours")
	public AlertView resolve(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID alertId) {
		return alerts.resolve(farmId, alertId, user);
	}

	@PostMapping("/{alertId}/work-order")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Raise a maintenance work order from the alert")
	public WorkOrderLink createWorkOrder(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID alertId) {
		return alerts.createWorkOrder(farmId, alertId, user);
	}

}
