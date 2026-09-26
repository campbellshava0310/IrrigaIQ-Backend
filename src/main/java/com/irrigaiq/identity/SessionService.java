package com.irrigaiq.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.security.AccessTokenIssuer;
import com.irrigaiq.security.AccessTokenIssuer.IssuedAccessToken;
import com.irrigaiq.security.SecurityProperties;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-token sessions with rotation and reuse detection (OAuth 2.0 Security BCP §4.14).
 *
 * <p>Every refresh replaces the presented token with a new one. If a token that was already
 * rotated is presented again, either the legitimate client or an attacker holds a stale copy; we
 * cannot tell which, so the whole session is revoked. A short leeway absorbs benign races such
 * as a client retrying a refresh whose response it never received.
 */
@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Duration PURGE_GRACE = Duration.ofDays(1);

	private final RefreshTokenRepository tokens;

	private final AccessTokenIssuer accessTokens;

	private final AuditService audit;

	private final SecurityProperties.RefreshToken properties;

	private final Clock clock;

	public SessionService(RefreshTokenRepository tokens, AccessTokenIssuer accessTokens, AuditService audit,
			SecurityProperties properties, Clock clock) {
		this.tokens = tokens;
		this.accessTokens = accessTokens;
		this.audit = audit;
		this.properties = properties.refreshToken();
		this.clock = clock;
	}

	/** Opens a new session for a user whose credentials the caller has already verified. */
	@Transactional(propagation = Propagation.MANDATORY)
	public AuthResult start(User user, ClientContext client) {
		Instant now = clock.instant();
		return issue(user, UUID.randomUUID(), now, client, now).result();
	}

	// Commit even when rejecting: reuse detection revokes the session and must not roll back.
	@Transactional(noRollbackFor = ApiException.class)
	public AuthResult rotate(String rawToken, ClientContext client) {
		RefreshToken current = tokens.findByTokenHashForUpdate(hash(rawToken)).orElseThrow(SessionService::invalid);
		Instant now = clock.instant();
		if (current.isRevoked()) {
			handleRevokedTokenPresented(current, now);
			throw invalid();
		}
		if (current.isExpired(now)) {
			throw invalid();
		}
		User user = current.getUser();
		if (!user.isEnabled()) {
			tokens.revokeSession(current.getSessionId(), RevocationReason.ACCOUNT_DISABLED, now);
			throw invalid();
		}
		// The session keeps its original client type; only network metadata is refreshed.
		ClientContext sessionClient = new ClientContext(current.getClientType(), client.ipAddress(),
				client.userAgent());
		IssuedSession next = issue(user, current.getSessionId(), current.getSessionStartedAt(), sessionClient, now);
		current.markRotated(next.token().getId(), now);
		return next.result();
	}

	/** Ends the session the token belongs to. Unknown or already-revoked tokens are ignored. */
	@Transactional
	public void logout(String rawToken) {
		Optional<RefreshToken> token = tokens.findByTokenHash(hash(rawToken));
		token.filter((t) -> !t.isRevoked()).ifPresent((t) -> {
			// Capture before the bulk update clears the persistence context.
			AuditEntry entry = AuditEntry.of(AuditAction.LOGOUT)
				.actor(t.getUser().getId(), t.getUser().getEmail())
				.target("SESSION", t.getSessionId());
			tokens.revokeSession(t.getSessionId(), RevocationReason.LOGOUT, clock.instant());
			audit.record(entry);
		});
	}

	@Transactional(readOnly = true)
	public List<RefreshToken> activeSessions(UUID userId) {
		return tokens.findActiveByUserId(userId, clock.instant());
	}

	@Transactional
	public boolean revoke(UUID userId, UUID sessionId, RevocationReason reason) {
		return tokens.revokeUserSession(userId, sessionId, reason, clock.instant()) > 0;
	}

	@Transactional
	public int revokeAll(UUID userId, RevocationReason reason) {
		return tokens.revokeAllForUser(userId, reason, clock.instant());
	}

	/** Revokes every session except {@code keepSessionId} (typically the caller's own device). */
	@Transactional
	public int revokeAllExcept(UUID userId, UUID keepSessionId, RevocationReason reason) {
		if (keepSessionId == null) {
			return revokeAll(userId, reason);
		}
		return tokens.revokeAllForUserExcept(userId, keepSessionId, reason, clock.instant());
	}

	@Transactional(readOnly = true)
	public long countActive() {
		return tokens.countActive(clock.instant());
	}

	@Scheduled(cron = "${irrigaiq.security.refresh-token.purge-cron:0 17 3 * * *}")
	@Transactional
	public void purgeExpiredTokens() {
		int deleted = tokens.deleteExpiredBefore(clock.instant().minus(PURGE_GRACE));
		if (deleted > 0) {
			log.info("Purged {} expired refresh tokens", deleted);
		}
	}

	private void handleRevokedTokenPresented(RefreshToken token, Instant now) {
		if (token.getRevokedReason() != RevocationReason.ROTATED) {
			return; // logged out, password changed, etc.: simply invalid
		}
		if (token.getRevokedAt().plus(properties.reuseLeeway()).isAfter(now)) {
			log.debug("Refresh token for session {} re-presented within leeway; rejecting without revocation",
					token.getSessionId());
			return;
		}
		User user = token.getUser(); // fetched with the token, so safe after the bulk update below
		int revoked = tokens.revokeSession(token.getSessionId(), RevocationReason.REUSE_DETECTED, now);
		log.warn("Refresh token reuse detected for user {} session {}; revoked {} token(s)", user.getId(),
				token.getSessionId(), revoked);
		audit.record(AuditEntry.of(AuditAction.REFRESH_TOKEN_REUSE)
			.failed()
			.actor(user.getId(), user.getEmail())
			.target("SESSION", token.getSessionId())
			.detail("revokedTokens", revoked));
	}

	private IssuedSession issue(User user, UUID sessionId, Instant sessionStartedAt, ClientContext client,
			Instant now) {
		String rawToken = generateToken();
		RefreshToken token = tokens.save(new RefreshToken(user, sessionId, hash(rawToken), client, sessionStartedAt,
				now, now.plus(properties.ttl())));
		IssuedAccessToken accessToken = accessTokens.issue(user.getId(), user.getEmail(), user.getFullName(),
				user.getRoles(), sessionId);
		AuthResult result = new AuthResult(user, sessionId, accessToken.value(), accessToken.expiresAt(), rawToken,
				token.getExpiresAt());
		return new IssuedSession(token, result);
	}

	private static String generateToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Tokens are 256-bit random values, so a fast unsalted hash is appropriate (unlike passwords). */
	static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static ApiException invalid() {
		return ApiException.unauthorized(ErrorCode.REFRESH_TOKEN_INVALID, "The session has expired; sign in again");
	}

	private record IssuedSession(RefreshToken token, AuthResult result) {
	}

}
