package com.irrigaiq.identity.web;

import java.time.Instant;
import java.util.UUID;

import com.irrigaiq.identity.ClientType;
import com.irrigaiq.identity.RefreshToken;

/** A signed-in device, as shown on the account security screen. */
public record SessionResponse(UUID id, ClientType clientType, String userAgent, String ipAddress, Instant startedAt,
		Instant lastUsedAt, Instant expiresAt, boolean current) {

	public static SessionResponse from(RefreshToken token, UUID currentSessionId) {
		return new SessionResponse(token.getSessionId(), token.getClientType(), token.getUserAgent(),
				token.getIpAddress(), token.getSessionStartedAt(), token.getLastUsedAt(), token.getExpiresAt(),
				token.getSessionId().equals(currentSessionId));
	}

}
