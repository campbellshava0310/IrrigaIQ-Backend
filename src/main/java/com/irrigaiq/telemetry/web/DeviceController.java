package com.irrigaiq.telemetry.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.telemetry.DeviceService;
import com.irrigaiq.telemetry.DeviceService.DeviceDetails;
import com.irrigaiq.telemetry.DeviceStatus;
import com.irrigaiq.telemetry.SensorType;
import com.irrigaiq.telemetry.TelemetryViews.DeviceView;
import com.irrigaiq.telemetry.TelemetryViews.IssuedKeyView;
import com.irrigaiq.telemetry.TelemetryViews.ReadingSeriesView;

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
@RequestMapping("/api/v1/farms/{farmId}/devices")
@Tag(name = "Devices")
public class DeviceController {

	private final DeviceService devices;

	public DeviceController(DeviceService devices) {
		this.devices = devices;
	}

	/**
	 * Soil probes read on their own scale: calibrate with the reading after a full irrigation drains
	 * (field capacity) and at wilting point. Leave both empty for the default scale (40 % / 15 %).
	 */
	public record RegisterDeviceRequest(@NotNull SensorType type,
			@NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9:._-]+",
					message = "letters, digits and : . _ - only") String deviceId,
			@Size(max = 160) String name, UUID fieldId, UUID assetId,
			@DecimalMin("1") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal fieldCapacityPct,
			@DecimalMin("0") @DecimalMax("99") @Digits(integer = 3, fraction = 2) BigDecimal wiltingPointPct) {
	}

	public record UpdateDeviceRequest(@Size(max = 160) String name, UUID fieldId, UUID assetId,
			@DecimalMin("1") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal fieldCapacityPct,
			@DecimalMin("0") @DecimalMax("99") @Digits(integer = 3, fraction = 2) BigDecimal wiltingPointPct,
			DeviceStatus status) {
	}

	@GetMapping
	@Operation(summary = "Devices of the farm with their latest reading and online state")
	public List<DeviceView> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return devices.list(farmId, user);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Register a device; the response holds its key, which is shown only once")
	public IssuedKeyView register(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@Valid @RequestBody RegisterDeviceRequest body) {
		return devices.register(farmId, user, new DeviceDetails(body.type(), body.deviceId(), body.name(),
				body.fieldId(), body.assetId(), body.fieldCapacityPct(), body.wiltingPointPct()));
	}

	@GetMapping("/{deviceId}")
	public DeviceView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID deviceId) {
		return devices.get(farmId, deviceId, user);
	}

	@PutMapping("/{deviceId}")
	@Operation(summary = "Rename, move, recalibrate, enable or disable a device")
	public DeviceView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID deviceId, @Valid @RequestBody UpdateDeviceRequest body) {
		return devices.update(farmId, deviceId, user, new DeviceDetails(null, null, body.name(), body.fieldId(),
				body.assetId(), body.fieldCapacityPct(), body.wiltingPointPct()), body.status());
	}

	@PostMapping("/{deviceId}/rotate-key")
	@Operation(summary = "Issue a new device key; the old key stops working immediately")
	public IssuedKeyView rotateKey(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID deviceId) {
		return devices.rotateKey(farmId, deviceId, user);
	}

	@DeleteMapping("/{deviceId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete a device and its readings")
	public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID deviceId) {
		devices.delete(farmId, deviceId, user);
	}

	@GetMapping("/{deviceId}/readings")
	@Operation(summary = "Hourly means of a device's readings (flow as litres per hour)")
	public ReadingSeriesView readings(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID deviceId, @RequestParam(defaultValue = "48") @Min(1) @Max(744) int hours) {
		return devices.readings(farmId, deviceId, user, hours);
	}

}
