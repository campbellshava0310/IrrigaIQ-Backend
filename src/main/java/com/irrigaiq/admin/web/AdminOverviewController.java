package com.irrigaiq.admin.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.admin.AdminOverviewService;
import com.irrigaiq.admin.AdminOverviewService.Overview;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/overview")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: overview")
public class AdminOverviewController {

	private final AdminOverviewService overview;

	public AdminOverviewController(AdminOverviewService overview) {
		this.overview = overview;
	}

	@GetMapping
	@Operation(summary = "Platform-wide user, session and farm counters")
	public Overview overview() {
		return overview.overview();
	}

}
