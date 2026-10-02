package com.irrigaiq.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.security.Role;
import com.irrigaiq.security.SecurityProperties;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Sign-in, registration and token refresh.
 *
 * <p>{@link #login} is deliberately not one transaction: a failed attempt must persist its
 * lockout bookkeeping and audit record even though the request fails, so each step commits on
 * its own.
 */
@Service
public class AuthService {

	private final UserRepository users;

	private final SessionService sessions;

	private final PasswordHasher passwords;

	private final AuditService audit;

	private final SecurityProperties properties;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public AuthService(UserRepository users, SessionService sessions, PasswordHasher passwords, AuditService audit,
			SecurityProperties properties, TransactionTemplate transactions, Clock clock) {
		this.users = users;
		this.sessions = sessions;
		this.passwords = passwords;
		this.audit = audit;
		this.properties = properties;
		this.transactions = transactions;
		this.clock = clock;
	}

	@Transactional
	public AuthResult register(String email, String password, String fullName, ClientContext client) {
		if (!properties.registrationEnabled()) {
			throw ApiException.forbidden(ErrorCode.REGISTRATION_DISABLED,
					"Self-service registration is disabled; ask an administrator for an account");
		}
		String normalizedEmail = EmailAddresses.normalize(email);
		if (users.existsByEmail(normalizedEmail)) {
			throw ApiException.conflict(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
		}
		Instant now = clock.instant();
		User newUser = new User(normalizedEmail, passwords.hash(password), fullName, Set.of(Role.FARM_MANAGER), now);
		newUser.registerSuccessfulLogin(now); // registration signs the user in
		User user = users.saveAndFlush(newUser);
		audit.record(AuditEntry.of(AuditAction.USER_REGISTERED)
			.actor(user.getId(), user.getEmail())
			.target("USER", user.getId()));
		return sessions.start(user, client);
	}

	public AuthResult login(String email, String password, ClientContext client) {
		String normalizedEmail = EmailAddresses.normalize(email);
		User user = users.findByEmail(normalizedEmail).orElse(null);
		if (user == null) {
			passwords.simulateVerification(password);
			recordFailedLogin(null, normalizedEmail, "UNKNOWN_ACCOUNT");
			throw invalidCredentials();
		}

		Instant now = clock.instant();
		if (user.isLocked(now)) {
			recordFailedLogin(user, normalizedEmail, "ACCOUNT_LOCKED");
			throw accountLocked(user.getLockedUntil());
		}

		if (!passwords.matches(password, user.getPasswordHash())) {
			boolean lockedNow = registerFailedAttempt(user);
			recordFailedLogin(user, normalizedEmail, "BAD_CREDENTIALS");
			if (lockedNow) {
				audit.record(AuditEntry.of(AuditAction.ACCOUNT_LOCKED)
					.actor(user.getId(), user.getEmail())
					.target("USER", user.getId())
					.detail("lockoutMinutes", properties.lockout().duration().toMinutes()));
			}
			throw invalidCredentials();
		}

		// Checked only after the password so a disabled account is not revealed to guessers.
		if (!user.isEnabled()) {
			recordFailedLogin(user, normalizedEmail, "ACCOUNT_DISABLED");
			throw ApiException.forbidden(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
		}

		return transactions.execute((status) -> {
			User managed = users.findById(user.getId()).orElseThrow();
			managed.registerSuccessfulLogin(clock.instant());
			if (passwords.needsRehash(managed.getPasswordHash())) {
				managed.upgradePasswordHash(passwords.hash(password));
			}
			AuthResult result = sessions.start(managed, client);
			audit.record(AuditEntry.of(AuditAction.LOGIN)
				.actor(managed.getId(), managed.getEmail())
				.target("SESSION", result.sessionId())
				.detail("clientType", client.clientType()));
			return result;
		});
	}

	public AuthResult refresh(String refreshToken, ClientContext client) {
		if (!StringUtils.hasText(refreshToken)) {
			throw ApiException.unauthorized(ErrorCode.REFRESH_TOKEN_INVALID, "No session to refresh; sign in again");
		}
		return sessions.rotate(refreshToken, client);
	}

	public void logout(String refreshToken) {
		if (StringUtils.hasText(refreshToken)) {
			sessions.logout(refreshToken);
		}
	}

	private boolean registerFailedAttempt(User user) {
		Boolean locked = transactions.execute((status) -> users.findByIdForUpdate(user.getId())
			.orElseThrow()
			.registerFailedLogin(properties.lockout().maxFailedAttempts(), properties.lockout().duration(),
					clock.instant()));
		return Boolean.TRUE.equals(locked);
	}

	private void recordFailedLogin(User user, String email, String reason) {
		audit.record(AuditEntry.of(AuditAction.LOGIN)
			.failed()
			.actor((user != null) ? user.getId() : null, email)
			.detail("reason", reason));
	}

	private static ApiException invalidCredentials() {
		return ApiException.unauthorized(ErrorCode.INVALID_CREDENTIALS, "Incorrect email or password");
	}

	private static ApiException accountLocked(Instant lockedUntil) {
		return new ApiException(HttpStatus.LOCKED, ErrorCode.ACCOUNT_LOCKED,
				"Too many failed sign-in attempts. Try again later.", Map.of("lockedUntil", lockedUntil.toString()));
	}

}
