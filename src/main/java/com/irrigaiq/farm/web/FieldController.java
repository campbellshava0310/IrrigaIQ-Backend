package com.irrigaiq.farm.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.farm.CropService;
import com.irrigaiq.farm.FarmViews.CropView;
import com.irrigaiq.farm.FarmViews.FieldView;
import com.irrigaiq.farm.FieldService;
import com.irrigaiq.farm.web.FarmRequests.CropRequest;
import com.irrigaiq.farm.web.FarmRequests.FieldRequest;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Fields and their crops, nested under the owning farm so access is always checked top-down. */
@RestController
@RequestMapping("/api/v1/farms/{farmId}/fields")
@Tag(name = "Fields & crops")
public class FieldController {

	private final FieldService fields;

	private final CropService crops;

	public FieldController(FieldService fields, CropService crops) {
		this.fields = fields;
		this.crops = crops;
	}

	@GetMapping
	public List<FieldView> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return fields.list(farmId, user);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public FieldView create(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@Valid @RequestBody FieldRequest body) {
		return fields.create(farmId, user, body.toDetails());
	}

	@GetMapping("/{fieldId}")
	public FieldView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId) {
		return fields.get(farmId, fieldId, user);
	}

	@PutMapping("/{fieldId}")
	public FieldView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId, @Valid @RequestBody FieldRequest body) {
		return fields.update(farmId, fieldId, user, body.toDetails());
	}

	@DeleteMapping("/{fieldId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId) {
		fields.delete(farmId, fieldId, user);
	}

	@GetMapping("/{fieldId}/crops")
	public List<CropView> listCrops(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId) {
		return crops.list(farmId, fieldId, user);
	}

	@PostMapping("/{fieldId}/crops")
	@ResponseStatus(HttpStatus.CREATED)
	public CropView createCrop(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId, @Valid @RequestBody CropRequest body) {
		return crops.create(farmId, fieldId, user, body.toDetails());
	}

	@PutMapping("/{fieldId}/crops/{cropId}")
	public CropView updateCrop(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId, @PathVariable UUID cropId, @Valid @RequestBody CropRequest body) {
		return crops.update(farmId, fieldId, cropId, user, body.toDetails());
	}

	@DeleteMapping("/{fieldId}/crops/{cropId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteCrop(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@PathVariable UUID fieldId, @PathVariable UUID cropId) {
		crops.delete(farmId, fieldId, cropId, user);
	}

}
