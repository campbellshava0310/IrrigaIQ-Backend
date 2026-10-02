package com.irrigaiq.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.irrigaiq.identity.StrongPasswordValidator;
import com.irrigaiq.identity.User;
import com.irrigaiq.security.Role;

import static org.assertj.core.api.Assertions.assertThat;

class UserLockoutTest {

	private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");

	private static final Duration LOCK = Duration.ofMinutes(15);

	private final User user = new User(" Farmer@Example.COM ", "{noop}x", " Farmer ", Set.of(Role.FARM_MANAGER), NOW);

	@Test
	void normalisesEmailAndName() {
		assertThat(user.getEmail()).isEqualTo("farmer@example.com");
		assertThat(user.getFullName()).isEqualTo("Farmer");
	}

	@Test
	void locksOnTheConfiguredAttempt() {
		assertThat(user.registerFailedLogin(3, LOCK, NOW)).isFalse();
		assertThat(user.registerFailedLogin(3, LOCK, NOW)).isFalse();
		assertThat(user.registerFailedLogin(3, LOCK, NOW)).isTrue();
		assertThat(user.isLocked(NOW.plus(LOCK).minusSeconds(1))).isTrue();
		assertThat(user.isLocked(NOW.plus(LOCK))).isFalse();
	}

	@Test
	void expiredLockStartsAFreshWindow() {
		for (int i = 0; i < 3; i++) {
			user.registerFailedLogin(3, LOCK, NOW);
		}
		Instant afterLock = NOW.plus(LOCK).plusSeconds(1);
		assertThat(user.registerFailedLogin(3, LOCK, afterLock)).isFalse();
		assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
	}

	@Test
	void successfulLoginClearsFailures() {
		user.registerFailedLogin(3, LOCK, NOW);
		user.registerSuccessfulLogin(NOW);
		assertThat(user.getFailedLoginAttempts()).isZero();
		assertThat(user.getLastLoginAt()).isEqualTo(NOW);
	}

	@Test
	void passwordPolicyCountsCharactersButCapsBytes() {
		StrongPasswordValidator validator = new StrongPasswordValidator();
		assertThat(validator.isValid("elevenchars", null)).isFalse();
		assertThat(validator.isValid("twelve chars", null)).isTrue();
		assertThat(validator.isValid("            ", null)).isFalse();
		assertThat(validator.isValid("a".repeat(72), null)).isTrue();
		assertThat(validator.isValid("é".repeat(37), null)).isFalse(); // 74 UTF-8 bytes
	}

}
