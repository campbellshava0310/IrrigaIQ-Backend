package com.irrigaiq.irrigation.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.farm.FarmViews.FieldView;
import com.irrigaiq.farm.IrrigationMethod;
import com.irrigaiq.irrigation.IrrigationService;
import com.irrigaiq.irrigation.IrrigationService.CompleteRequest;
import com.irrigaiq.irrigation.IrrigationService.IrrigationSetup;
import com.irrigaiq.irrigation.IrrigationService.LogRequest;
import com.irrigaiq.irrigation.IrrigationService.ObservationRequest;
import com.irrigaiq.irrigation.IrrigationSource;
import com.irrigaiq.irrigation.IrrigationStatus;
import com.irrigaiq.irrigation.IrrigationViews.EventView;
import com.irrigaiq.irrigation.IrrigationViews.FieldStatusView;
import com.irrigaiq.irrigation.IrrigationViews.ObservationView;
import com.irrigaiq.irrigation.IrrigationViews.WaterBalanceView;
import com.irrigaiq.irrigation.ObservationMethod;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/farms/{farmId}")
@Tag(name = "Irrigation")
public class IrrigationController {

	private final IrrigationService irrigation;

	public IrrigationController(IrrigationService irrigation) {
		this.irrigation = irrigation;
	}

	public record SetupRequest(IrrigationMethod method,
			@Positive @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal applicationRateMmH,
			@DecimalMin("0.30") @DecimalMax("1.00") @Digits(integer = 1, fraction = 2) BigDecimal efficiency,
			UUID pumpAssetId, Boolean mulched) {
	}

	/**
	 * Supply one of gross depth or volume; otherwise the depth is derived from run time and the
	 * field's application rate.
	 */
	public record LogIrrigationRequest(IrrigationStatus status, IrrigationSource source, @NotNull Instant startedAt,
			@Min(1) @Max(1440) int durationMinutes, @Positive @DecimalMax("500") Double grossDepthMm,
			@Positive Double volumeM3, UUID pumpAssetId, @PositiveOrZero Double energyKwh,
			@Size(max = 500) String notes) {
	}

	public record CompleteIrrigationRequest(Instant startedAt, @Min(1) @Max(1440) Integer durationMinutes,
			@Positive Double volumeM3, @PositiveOrZero Double energyKwh, @Size(max = 500) String notes) {
	}

	public record SkipRequest(@Size(max = 500) String reason) {
	}

	/** Depletion of available water: 0 = field capacity, 100 = wilting point. */
	public record SoilObservationRequest(Instant observedAt,
			@NotNull @DecimalMin("0") @DecimalMax("100") Double depletionPct, ObservationMethod method,
			@Size(max = 500) String notes) {
	}

	@GetMapping("/irrigation/status")
	@Operation(summary = "Water status, recommendation and next action for every field")
	public List<FieldStatusView> status(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return irrigation.status(farmId, user);
	}

	@GetMapping("/fields/{fieldId}/water-balance")
	@Operation(summary = "Daily soil-water balance (history and forecast), recommendation and season outlook")
	public WaterBalanceView waterBalance(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId, @RequestParam(defaultValue = "30") @Min(7) @Max(180) int historyDays) {
		return irrigation.waterBalance(farmId, fieldId, user, historyDays);
	}

	@PutMapping("/fields/{fieldId}/irrigation-setup")
	@Operation(summary = "Irrigation system of a field: method, measured rate/efficiency, supplying pump, mulch")
	public FieldView configure(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId, @Valid @RequestBody SetupRequest body) {
		return irrigation.configure(farmId, fieldId, user,
				new IrrigationSetup(body.method(), body.applicationRateMmH(), body.efficiency(), body.pumpAssetId(),
						body.mulched()));
	}

	@GetMapping("/irrigation/events")
	@Operation(summary = "Planned and completed irrigations in a date range (farm time zone)")
	public List<EventView> events(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID fieldId, @RequestParam(required = false) IrrigationStatus status) {
		if (to.isBefore(from) || from.plusDays(400).isBefore(to)) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "The range must be 0-400 days, from before to");
		}
		return irrigation.events(farmId, user, from, to, fieldId, status);
	}

	@PostMapping("/fields/{fieldId}/irrigation/events")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Log a completed irrigation or plan one")
	public EventView log(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId, @Valid @RequestBody LogIrrigationRequest body) {
		return irrigation.log(farmId, fieldId, user, new LogRequest(body.status(), body.source(), body.startedAt(),
				body.durationMinutes(), body.grossDepthMm(), body.volumeM3(), body.pumpAssetId(), body.energyKwh(),
				body.notes()));
	}

	@PostMapping("/fields/{fieldId}/irrigation/plan-recommended")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Plan the currently recommended irrigation in the pump's power window")
	public EventView planRecommended(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId) {
		return irrigation.planRecommended(farmId, fieldId, user);
	}

	@PostMapping("/irrigation/events/{eventId}/complete")
	@Operation(summary = "Confirm a planned irrigation ran (optionally with measured volume)")
	public EventView complete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID eventId, @Valid @RequestBody(required = false) CompleteIrrigationRequest body) {
		CompleteIrrigationRequest request = (body != null) ? body
				: new CompleteIrrigationRequest(null, null, null, null, null);
		return irrigation.complete(farmId, eventId, user, new CompleteRequest(request.startedAt(),
				request.durationMinutes(), request.volumeM3(), request.energyKwh(), request.notes()));
	}

	@PostMapping("/irrigation/events/{eventId}/skip")
	@Operation(summary = "Skip a planned irrigation (e.g. rain arrived, power outage)")
	public EventView skip(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID eventId, @Valid @RequestBody(required = false) SkipRequest body) {
		return irrigation.skip(farmId, eventId, user, (body != null) ? body.reason() : null);
	}

	@DeleteMapping("/irrigation/events/{eventId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete a mistaken irrigation record")
	public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID eventId) {
		irrigation.delete(farmId, eventId, user);
	}

	@GetMapping("/fields/{fieldId}/soil-observations")
	public List<ObservationView> observations(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID farmId, @PathVariable UUID fieldId) {
		return irrigation.observations(farmId, fieldId, user);
	}

	@PostMapping("/fields/{fieldId}/soil-observations")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Calibrate the water balance with a soil-moisture check (feel method, probe or sensor)")
	public ObservationView observe(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId, @Valid @RequestBody SoilObservationRequest body) {
		return irrigation.observe(farmId, fieldId, user,
				new ObservationRequest(body.observedAt(), body.depletionPct(), body.method(), body.notes()));
	}

}
