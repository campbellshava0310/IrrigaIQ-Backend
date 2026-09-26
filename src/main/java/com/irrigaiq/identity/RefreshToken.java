package com.irrigaiq.identity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.irrigaiq.common.persistence.BaseEntity;

/**
 * One issued refresh token. Only the SHA-256 hash of the opaque value is stored. Tokens sharing a
 * {@code sessionId} form a rotation chain representing a single signed-in device.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@Column(name = "session_id", nullable = false, updatable = false)
	private UUID sessionId;

	@Column(name = "token_hash", nullable = false, updatable = false, length = 64)
	private String tokenHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "client_type", nullable = false, updatable = false, length = 16)
	private ClientType clientType;

	@Column(name = "session_started_at", nullable = false, updatable = false)
	private Instant sessionStartedAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "last_used_at")
	private Instant lastUsedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "revoked_reason", length = 32)
	private RevocationReason revokedReason;

	@Column(name = "replaced_by_id")
	private UUID replacedById;

	@Column(name = "user_agent", length = 512)
	private String userAgent;

	@Column(name = "ip_address", length = 64)
	private String ipAddress;

	protected RefreshToken() {
	}

	RefreshToken(User user, UUID sessionId, String tokenHash, ClientContext client, Instant sessionStartedAt,
			Instant issuedAt, Instant expiresAt) {
		this.user = user;
		this.sessionId = sessionId;
		this.tokenHash = tokenHash;
		this.clientType = client.clientType();
		this.userAgent = client.userAgent();
		this.ipAddress = client.ipAddress();
		this.sessionStartedAt = sessionStartedAt;
		this.lastUsedAt = issuedAt;
		this.expiresAt = expiresAt;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isExpired(Instant now) {
		return !expiresAt.isAfter(now);
	}

	void markRotated(UUID successorId, Instant now) {
		this.revokedAt = now;
		this.revokedReason = RevocationReason.ROTATED;
		this.replacedById = successorId;
	}

	public User getUser() {
		return user;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public ClientType getClientType() {
		return clientType;
	}

	public Instant getSessionStartedAt() {
		return sessionStartedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getLastUsedAt() {
		return lastUsedAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public RevocationReason getRevokedReason() {
		return revokedReason;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public String getIpAddress() {
		return ipAddress;
	}

}
