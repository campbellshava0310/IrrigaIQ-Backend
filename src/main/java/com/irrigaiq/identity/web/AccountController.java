package com.irrigaiq.identity.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.identity.AccountService;
import com.irrigaiq.identity.SessionService;
import com.irrigaiq.identity.web.AuthRequests.ChangePasswordRequest;
import com.irrigaiq.identity.web.AuthRequests.UpdateProfileRequest;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own profile, password and devices. */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Account")
public class AccountController {

	private final AccountService accounts;

	private final SessionService sessions;

	public AccountController(AccountService accounts, SessionService sessions) {
		this.accounts = accounts;
		this.sessions = sessions;
	}

	@GetMapping
	@Operation(summary = "Current user profile")
	public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
		return UserResponse.from(accounts.get(principal.id()));
	}

	@PatchMapping
	@Operation(summary = "Update the current user's profile")
	public UserResponse updateProfile(@AuthenticationPrincipal AuthenticatedUser principal,
			@Valid @RequestBody UpdateProfileRequest body) {
		return UserResponse.from(accounts.updateProfile(principal.id(), body.fullName()));
	}

	@PostMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Change password; signs out all other devices")
	public void changePassword(@AuthenticationPrincipal AuthenticatedUser principal,
			@Valid @RequestBody ChangePasswordRequest body) {
		accounts.changePassword(principal, body.currentPassword(), body.newPassword());
	}

	@GetMapping("/sessions")
	@Operation(summary = "Devices currently signed in to this account")
	public List<SessionResponse> sessions(@AuthenticationPrincipal AuthenticatedUser principal) {
		return sessions.activeSessions(principal.id())
			.stream()
			.map((token) -> SessionResponse.from(token, principal.sessionId()))
			.toList();
	}

	@DeleteMapping("/sessions/{sessionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Sign out a specific device")
	public void revokeSession(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID sessionId) {
		accounts.revokeSession(principal, sessionId);
	}

	@DeleteMapping("/sessions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Sign out every device except this one")
	public void revokeOtherSessions(@AuthenticationPrincipal AuthenticatedUser principal) {
		accounts.revokeOtherSessions(principal);
	}

}
