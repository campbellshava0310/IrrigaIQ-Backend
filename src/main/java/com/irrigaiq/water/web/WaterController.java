package com.irrigaiq.water.web;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.water.WaterService;
import com.irrigaiq.water.WaterService.WaterSummary;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/farms/{farmId}/water")
@Tag(name = "Water")
public class WaterController {

	private final WaterService water;

	public WaterController(WaterService water) {
		this.water = water;
	}

	@GetMapping("/summary")
	@Operation(summary = "Water applied vs crop requirement, savings vs a calendar schedule, energy and allocations",
			description = "Defaults to the 30 days ending today in the farm's time zone; at most 180 days.")
	public WaterSummary summary(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return water.summary(farmId, user, from, to);
	}

}
