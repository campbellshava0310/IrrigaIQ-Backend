package com.irrigaiq.farm.web;

import java.util.UUID;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.common.web.PageResponse;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.farm.FarmViews.FarmView;
import com.irrigaiq.farm.web.FarmRequests.FarmRequest;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
@RequestMapping("/api/v1/farms")
@Tag(name = "Farms")
public class FarmController {

	private final FarmService farms;

	public FarmController(FarmService farms) {
		this.farms = farms;
	}

	@GetMapping
	@Operation(summary = "Farms visible to the caller (own farms; administrators see all)")
	public PageResponse<FarmView> list(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(required = false) String q,
			@PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
		return PageResponse.of(farms.list(user, q, pageable));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a farm owned by the caller")
	public FarmView create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody FarmRequest body) {
		return farms.create(user, body.toDetails());
	}

	@GetMapping("/{farmId}")
	public FarmView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return farms.get(farmId, user);
	}

	@PutMapping("/{farmId}")
	public FarmView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@Valid @RequestBody FarmRequest body) {
		return farms.update(farmId, user, body.toDetails());
	}

	@DeleteMapping("/{farmId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete a farm with all of its fields, crops and telemetry")
	public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		farms.delete(farmId, user);
	}

}
