package com.irrigaiq.admin.web;

import java.time.Clock;
import java.util.UUID;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.admin.AdminUserService;
import com.irrigaiq.admin.web.AdminDtos.AdminUserResponse;
import com.irrigaiq.admin.web.AdminDtos.CreateUserRequest;
import com.irrigaiq.admin.web.AdminDtos.UpdateUserRequest;
import com.irrigaiq.common.web.PageResponse;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.security.Role;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: users")
public class AdminUserController {

	private final AdminUserService users;

	private final Clock clock;

	public AdminUserController(AdminUserService users, Clock clock) {
		this.users = users;
		this.clock = clock;
	}

	@GetMapping
	@Operation(summary = "Search users by email/name, role and status")
	public PageResponse<AdminUserResponse> search(@RequestParam(required = false) String q,
			@RequestParam(required = false) Role role, @RequestParam(required = false) Boolean enabled,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return PageResponse.of(users.search(q, role, enabled, pageable),
				(user) -> AdminUserResponse.from(user, clock.instant()));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a user with a temporary password")
	public AdminUserResponse create(@Valid @RequestBody CreateUserRequest body) {
		return AdminUserResponse.from(
				users.create(body.email(), body.fullName(), body.temporaryPassword(), body.roles()), clock.instant());
	}

	@GetMapping("/{userId}")
	public AdminUserResponse get(@PathVariable UUID userId) {
		return AdminUserResponse.from(users.get(userId), clock.instant());
	}

	@PatchMapping("/{userId}")
	@Operation(summary = "Update name, roles or enabled status; disabling signs the user out everywhere")
	public AdminUserResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID userId,
			@Valid @RequestBody UpdateUserRequest body) {
		return AdminUserResponse.from(users.update(actor, userId, body.fullName(), body.roles(), body.enabled()),
				clock.instant());
	}

	@PostMapping("/{userId}/unlock")
	@Operation(summary = "Clear a lockout caused by failed sign-in attempts")
	public AdminUserResponse unlock(@PathVariable UUID userId) {
		return AdminUserResponse.from(users.unlock(userId), clock.instant());
	}

	@DeleteMapping("/{userId}/sessions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Sign the user out of every device")
	public void revokeSessions(@PathVariable UUID userId) {
		users.revokeSessions(userId);
	}

}
