package com.irrigaiq.identity.web;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.irrigaiq.identity.User;
import com.irrigaiq.security.Role;

public record UserResponse(UUID id, String email, String fullName, Set<Role> roles, boolean enabled,
		Instant lastLoginAt, Instant createdAt) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRoles(), user.isEnabled(),
				user.getLastLoginAt(), user.getCreatedAt());
	}

}
