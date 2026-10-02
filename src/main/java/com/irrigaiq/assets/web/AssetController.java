package com.irrigaiq.assets.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.assets.AssetConditionCheck.CheckDetails;
import com.irrigaiq.assets.AssetDetails;
import com.irrigaiq.assets.AssetService;
import com.irrigaiq.assets.AssetStatus;
import com.irrigaiq.assets.AssetType;
import com.irrigaiq.assets.AssetViews.AssetDetailView;
import com.irrigaiq.assets.AssetViews.AssetView;
import com.irrigaiq.assets.AssetViews.ConditionCheckView;
import com.irrigaiq.assets.Criticality;
import com.irrigaiq.assets.OverallCondition;
import com.irrigaiq.assets.PowerSource;
import com.irrigaiq.assets.VibrationLevel;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/farms/{farmId}/assets")
@Tag(name = "Infrastructure")
public class AssetController {

	private final AssetService assets;

	public AssetController(AssetService assets) {
		this.assets = assets;
	}

	public record AssetRequest(@NotNull AssetType type, @NotBlank @Size(max = 160) String name, UUID fieldId,
			UUID parentId, @Size(max = 120) String manufacturer, @Size(max = 120) String model,
			@Size(max = 120) String serialNumber, @PastOrPresent LocalDate installedOn, Criticality criticality,
			AssetStatus status, PowerSource powerSource, @Positive @Digits(integer = 6, fraction = 2) BigDecimal ratedPowerKw,
			@Positive @Digits(integer = 8, fraction = 2) BigDecimal ratedFlowM3h,
			@Positive @Digits(integer = 6, fraction = 2) BigDecimal ratedHeadM,
			@Positive @Digits(integer = 12, fraction = 2) BigDecimal capacityM3,
			@Positive @Digits(integer = 12, fraction = 2) BigDecimal annualAllocationM3, LocalTime operatingWindowStart,
			LocalTime operatingWindowEnd, @Min(1) @Max(3650) Integer serviceIntervalDays,
			@Min(1) @Max(100000) Integer serviceIntervalHours, @Min(1) @Max(100) Integer expectedLifeYears,
			@PastOrPresent LocalDate lastServiceOn,
			@Positive @Digits(integer = 4, fraction = 4) BigDecimal baselineEnergyKwhPerM3,
			@PositiveOrZero @Digits(integer = 9, fraction = 1) BigDecimal operatingHours,
			@Size(max = 1000) String notes) {

		AssetDetails toDetails() {
			return new AssetDetails(type, name, fieldId, parentId, manufacturer, model, serialNumber, installedOn,
					criticality, status, powerSource, ratedPowerKw, ratedFlowM3h, ratedHeadM, capacityM3,
					annualAllocationM3, operatingWindowStart, operatingWindowEnd, serviceIntervalDays,
					serviceIntervalHours, expectedLifeYears, lastServiceOn, baselineEnergyKwhPerM3, operatingHours, notes);
		}

	}

	public record StatusRequest(@NotNull AssetStatus status) {
	}

	/**
	 * A field inspection. Pump checks record flow and power so energy per m³ can be tracked; filter
	 * checks record differential pressure; borehole checks record water level (m below ground).
	 */
	public record ConditionCheckRequest(Instant checkedAt, @NotNull OverallCondition overallCondition,
			@PositiveOrZero @DecimalMax("100") BigDecimal pressureBar,
			@PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal flowM3h,
			@PositiveOrZero @Digits(integer = 6, fraction = 2) BigDecimal powerKw,
			@PositiveOrZero @Digits(integer = 9, fraction = 1) BigDecimal hourMeter, VibrationLevel vibration,
			Boolean leakDetected, @PositiveOrZero @DecimalMax("10") BigDecimal filterDpBar,
			@Digits(integer = 5, fraction = 2) BigDecimal waterLevelM, @Size(max = 1000) String notes) {

		CheckDetails toDetails() {
			return new CheckDetails(checkedAt, overallCondition, pressureBar, flowM3h, powerKw, hourMeter, vibration,
					leakDetected, filterDpBar, waterLevelM, notes);
		}

	}

	@GetMapping
	@Operation(summary = "All infrastructure of the farm with health score, risk and next service")
	public List<AssetView> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return assets.list(farmId, user);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AssetView create(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@Valid @RequestBody AssetRequest body) {
		return assets.create(farmId, user, body.toDetails());
	}

	@GetMapping("/{assetId}")
	@Operation(summary = "Asset with health explanation, recent inspections and connected assets")
	public AssetDetailView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID assetId) {
		return assets.get(farmId, assetId, user);
	}

	@PutMapping("/{assetId}")
	public AssetView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID assetId, @Valid @RequestBody AssetRequest body) {
		return assets.update(farmId, assetId, user, body.toDetails());
	}

	@PatchMapping("/{assetId}/status")
	@Operation(summary = "Report a breakdown or return an asset to service")
	public AssetView changeStatus(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID assetId, @Valid @RequestBody StatusRequest body) {
		return assets.changeStatus(farmId, assetId, user, body.status());
	}

	@DeleteMapping("/{assetId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID assetId) {
		assets.delete(farmId, assetId, user);
	}

	@GetMapping("/{assetId}/condition-checks")
	public List<ConditionCheckView> checks(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID assetId, @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
		return assets.checks(farmId, assetId, user, limit);
	}

	@PostMapping("/{assetId}/condition-checks")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Record an inspection (pump flow/power, filter pressure drop, borehole level, ...)")
	public ConditionCheckView recordCheck(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID assetId, @Valid @RequestBody ConditionCheckRequest body) {
		return assets.recordCheck(farmId, assetId, user, body.toDetails());
	}

}
