package com.irrigaiq.identity;

import java.time.Instant;
import java.util.UUID;

/** Outcome of a successful sign-in, registration or refresh. */
public record AuthResult(User user, UUID sessionId, String accessToken, Instant accessTokenExpiresAt,
		String refreshToken, Instant refreshTokenExpiresAt) {
}
