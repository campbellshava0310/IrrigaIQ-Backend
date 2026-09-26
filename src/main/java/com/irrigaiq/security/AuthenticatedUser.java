package com.irrigaiq.security;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated principal, derived from a verified access token. Inject into controllers with
 * {@code @AuthenticationPrincipal AuthenticatedUser user}.
 *
 * @param sessionId the refresh-token session the access token was minted for
 */
public record AuthenticatedUser(UUID id, String email, String fullName, Set<Role> roles, UUID sessionId) {

	public AuthenticatedUser {
		roles = Set.copyOf(roles);
	}

	public boolean hasRole(Role role) {
		return roles.contains(role);
	}

	public boolean isAdmin() {
		return hasRole(Role.ADMIN);
	}

}
