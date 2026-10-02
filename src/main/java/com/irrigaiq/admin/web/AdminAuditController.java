package com.irrigaiq.admin.web;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.admin.web.AdminDtos.AuditEventResponse;
import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditOutcome;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.audit.AuditService.AuditSearch;
import com.irrigaiq.common.web.PageResponse;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit-events")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: audit")
public class AdminAuditController {

	private final AuditService audit;

	public AdminAuditController(AuditService audit) {
		this.audit = audit;
	}

	@GetMapping
	@Operation(summary = "Search the audit trail (newest first)")
	public PageResponse<AuditEventResponse> search(@RequestParam(required = false) AuditAction action,
			@RequestParam(required = false) AuditOutcome outcome, @RequestParam(required = false) UUID actorId,
			@RequestParam(required = false) String targetId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@PageableDefault(size = 50, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
		AuditSearch search = new AuditSearch(action, outcome, actorId, targetId, from, to);
		return PageResponse.of(audit.search(search, pageable), AuditEventResponse::from);
	}

}
