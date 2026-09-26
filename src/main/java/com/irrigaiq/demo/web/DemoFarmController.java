package com.irrigaiq.demo.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.demo.DemoFarmService;
import com.irrigaiq.demo.DemoFarmService.DemoFarmResult;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/demo-farm")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: demo data")
public class DemoFarmController {

	private final DemoFarmService demo;

	public DemoFarmController(DemoFarmService demo) {
		this.demo = demo;
	}

	/** @param ownerEmail farm owner; defaults to the calling administrator */
	public record DemoFarmRequest(@Email @Size(max = 320) String ownerEmail) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create the summit demo farm (fields, crops, infrastructure, history and scenarios)")
	public DemoFarmResult create(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody(required = false) DemoFarmRequest body) {
		return demo.create(user, (body != null) ? body.ownerEmail() : null);
	}

}
