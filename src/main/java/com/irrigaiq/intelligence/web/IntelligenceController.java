package com.irrigaiq.intelligence.web;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.intelligence.BriefingService;
import com.irrigaiq.intelligence.BriefingService.Briefing;
import com.irrigaiq.intelligence.DashboardService;
import com.irrigaiq.intelligence.DashboardService.Dashboard;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/farms/{farmId}")
@Tag(name = "Farm intelligence")
public class IntelligenceController {

	private final DashboardService dashboard;

	private final BriefingService briefing;

	public IntelligenceController(DashboardService dashboard, BriefingService briefing) {
		this.dashboard = dashboard;
		this.briefing = briefing;
	}

	@GetMapping("/dashboard")
	@Operation(summary = "Today's actions, field water status, water KPIs, asset health and alerts in one call")
	public Dashboard dashboard(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return dashboard.dashboard(farmId, user);
	}

	@GetMapping("/briefing")
	@Operation(summary = "Daily farm briefing written from verified platform data")
	public Briefing briefing(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return briefing.briefing(farmId, user);
	}

}
