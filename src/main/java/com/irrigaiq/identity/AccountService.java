package com.irrigaiq.identity;

import java.time.Clock;
import java.util.UUID;

import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Self-service account management for the signed-in user. */
@Service
public class AccountService {

	private final UserRepository users;

	private final SessionService sessions;

	private final PasswordHasher passwords;

	private final AuditService audit;

	private final Clock clock;

	public AccountService(UserRepository users, SessionService sessions, PasswordHasher passwords, AuditService audit,
			Clock clock) {
		this.users = users;
		this.sessions = sessions;
		this.passwords = passwords;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public User get(UUID userId) {
		return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
	}

	@Transactional
	public User updateProfile(UUID userId, String fullName) {
		User user = get(userId);
		user.rename(fullName);
		audit.record(AuditEntry.of(AuditAction.PROFILE_UPDATED).target("USER", userId));
		return user;
	}

	/**
	 * Changes the password and signs out every other device. The current session survives so the
	 * user is not bounced out of the screen they are on. Wrong-password failures are 400, never
	 * 401, so clients do not mistake them for an expired session.
	 */
	@Transactional
	public void changePassword(AuthenticatedUser principal, String currentPassword, String newPassword) {
		User user = get(principal.id());
		if (!passwords.matches(currentPassword, user.getPasswordHash())) {
			throw ApiException.badRequest(ErrorCode.CURRENT_PASSWORD_INCORRECT, "The current password is incorrect");
		}
		if (passwords.matches(newPassword, user.getPasswordHash())) {
			throw ApiException.badRequest(ErrorCode.PASSWORD_REUSE,
					"The new password must be different from the current one");
		}
		user.changePassword(passwords.hash(newPassword), clock.instant());
		int revoked = sessions.revokeAllExcept(user.getId(), principal.sessionId(), RevocationReason.PASSWORD_CHANGED);
		audit.record(AuditEntry.of(AuditAction.PASSWORD_CHANGED)
			.target("USER", principal.id())
			.detail("revokedSessions", revoked));
	}

	@Transactional
	public void revokeSession(AuthenticatedUser principal, UUID sessionId) {
		if (!sessions.revoke(principal.id(), sessionId, RevocationReason.USER_REVOKED)) {
			throw ApiException.notFound("Session");
		}
		audit.record(AuditEntry.of(AuditAction.SESSION_REVOKED).target("SESSION", sessionId));
	}

	@Transactional
	public int revokeOtherSessions(AuthenticatedUser principal) {
		int revoked = sessions.revokeAllExcept(principal.id(), principal.sessionId(), RevocationReason.USER_REVOKED);
		audit.record(AuditEntry.of(AuditAction.SESSION_REVOKED)
			.target("USER", principal.id())
			.detail("scope", "OTHER_SESSIONS")
			.detail("revokedSessions", revoked));
		return revoked;
	}

}
