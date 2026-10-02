package com.irrigaiq.identity.web;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.irrigaiq.identity.AuthResult;

/**
 * @param expiresIn access-token lifetime in seconds (OAuth 2.0 convention)
 * @param refreshToken present for mobile clients only; web clients receive an HttpOnly cookie
 */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, Instant accessTokenExpiresAt,
		@JsonInclude(JsonInclude.Include.NON_NULL) String refreshToken, Instant refreshTokenExpiresAt,
		UserResponse user) {

	public static AuthResponse from(AuthResult result, Instant now, boolean includeRefreshToken) {
		long expiresIn = Math.max(0, Duration.between(now, result.accessTokenExpiresAt()).toSeconds());
		return new AuthResponse(result.accessToken(), "Bearer", expiresIn, result.accessTokenExpiresAt(),
				includeRefreshToken ? result.refreshToken() : null, result.refreshTokenExpiresAt(),
				UserResponse.from(result.user()));
	}

}
