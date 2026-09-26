package com.irrigaiq.admin.web;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEvent;
import com.irrigaiq.audit.AuditOutcome;
import com.irrigaiq.identity.StrongPassword;
import com.irrigaiq.identity.User;
import com.irrigaiq.security.Role;

/** Request and response bodies for the admin API. */
public final class AdminDtos {

	private AdminDtos() {
	}

	public record CreateUserRequest(@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(max = 200) String fullName, @StrongPassword String temporaryPassword,
			@NotEmpty Set<Role> roles) {
	}

	/** Partial update; omitted (null) members are left unchanged. */
	public record UpdateUserRequest(@Size(max = 200) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String fullName,
			@Size(min = 1, message = "must contain at least one role") Set<Role> roles, Boolean enabled) {
	}

	public record AdminUserResponse(UUID id, String email, String fullName, Set<Role> roles, boolean enabled,
			boolean locked, Instant lockedUntil, int failedLoginAttempts, Instant lastLoginAt, Instant passwordChangedAt,
			Instant createdAt, Instant updatedAt) {

		static AdminUserResponse from(User user, Instant now) {
			return new AdminUserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRoles(),
					user.isEnabled(), user.isLocked(now), user.getLockedUntil(), user.getFailedLoginAttempts(),
					user.getLastLoginAt(), user.getPasswordChangedAt(), user.getCreatedAt(), user.getUpdatedAt());
		}

	}

	public record AuditEventResponse(UUID id, Instant occurredAt, AuditAction action, AuditOutcome outcome,
			UUID actorUserId, String actorEmail, String targetType, String targetId, String ipAddress,
			String userAgent, String requestId, Map<String, Object> details) {

		static AuditEventResponse from(AuditEvent event) {
			return new AuditEventResponse(event.getId(), event.getOccurredAt(), event.getAction(), event.getOutcome(),
					event.getActorUserId(), event.getActorEmail(), event.getTargetType(), event.getTargetId(),
					event.getIpAddress(), event.getUserAgent(), event.getRequestId(), event.getDetails());
		}

	}

}
