package com.irrigaiq.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import com.irrigaiq.common.persistence.BaseEntity;
import com.irrigaiq.security.Role;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

	@Column(nullable = false, length = 320)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "full_name", nullable = false, length = 200)
	private String fullName;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 32)
	private Set<Role> roles = new HashSet<>();

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(name = "failed_login_attempts", nullable = false)
	private int failedLoginAttempts;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "password_changed_at", nullable = false)
	private Instant passwordChangedAt;

	protected User() {
	}

	public User(String email, String passwordHash, String fullName, Set<Role> roles, Instant now) {
		this.email = EmailAddresses.normalize(email);
		this.passwordHash = passwordHash;
		this.fullName = fullName.strip();
		this.roles.addAll(roles);
		this.passwordChangedAt = now;
	}

	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	/**
	 * Records a failed password attempt.
	 * @return {@code true} if this attempt locked the account
	 */
	public boolean registerFailedLogin(int maxAttempts, Duration lockDuration, Instant now) {
		if (lockedUntil != null && !lockedUntil.isAfter(now)) {
			// A previous lock has expired: start a fresh window instead of re-locking on one miss.
			lockedUntil = null;
			failedLoginAttempts = 0;
		}
		failedLoginAttempts++;
		if (failedLoginAttempts >= maxAttempts) {
			lockedUntil = now.plus(lockDuration);
			return true;
		}
		return false;
	}

	public void registerSuccessfulLogin(Instant now) {
		failedLoginAttempts = 0;
		lockedUntil = null;
		lastLoginAt = now;
	}

	public void unlock() {
		failedLoginAttempts = 0;
		lockedUntil = null;
	}

	public void changePassword(String newPasswordHash, Instant now) {
		this.passwordHash = newPasswordHash;
		this.passwordChangedAt = now;
	}

	/** Re-encodes the stored hash with the current default algorithm; not a password change. */
	public void upgradePasswordHash(String upgradedHash) {
		this.passwordHash = upgradedHash;
	}

	public void rename(String fullName) {
		this.fullName = fullName.strip();
	}

	public void replaceRoles(Set<Role> newRoles) {
		roles.clear();
		roles.addAll(newRoles);
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean hasRole(Role role) {
		return roles.contains(role);
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getFullName() {
		return fullName;
	}

	public Set<Role> getRoles() {
		return roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public int getFailedLoginAttempts() {
		return failedLoginAttempts;
	}

	public Instant getLockedUntil() {
		return lockedUntil;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}

	public Instant getPasswordChangedAt() {
		return passwordChangedAt;
	}

}
