package com.irrigaiq.admin;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;

import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.identity.EmailAddresses;
import com.irrigaiq.identity.PasswordHasher;
import com.irrigaiq.identity.RevocationReason;
import com.irrigaiq.identity.SessionService;
import com.irrigaiq.identity.User;
import com.irrigaiq.identity.UserRepository;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.security.Role;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * User administration. Guards against the two ways an admin can lock everyone out: removing their
 * own admin access, and removing the last enabled administrator.
 */
@Service
public class AdminUserService {

	private final UserRepository users;

	private final SessionService sessions;

	private final PasswordHasher passwords;

	private final AuditService audit;

	private final Clock clock;

	public AdminUserService(UserRepository users, SessionService sessions, PasswordHasher passwords,
			AuditService audit, Clock clock) {
		this.users = users;
		this.sessions = sessions;
		this.passwords = passwords;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Page<User> search(String query, Role role, Boolean enabled, Pageable pageable) {
		Specification<User> specification = (root, criteriaQuery, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (StringUtils.hasText(query)) {
				String pattern = "%" + query.strip().toLowerCase(Locale.ROOT) + "%";
				predicates.add(cb.or(cb.like(root.get("email"), pattern), cb.like(cb.lower(root.get("fullName")), pattern)));
			}
			if (role != null) {
				predicates.add(cb.isMember(role, root.get("roles")));
			}
			if (enabled != null) {
				predicates.add(cb.equal(root.get("enabled"), enabled));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		return users.findAll(specification, pageable);
	}

	@Transactional(readOnly = true)
	public User get(UUID userId) {
		return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
	}

	@Transactional
	public User create(String email, String fullName, String temporaryPassword, Set<Role> roles) {
		String normalizedEmail = EmailAddresses.normalize(email);
		if (users.existsByEmail(normalizedEmail)) {
			throw ApiException.conflict(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
		}
		User user = users.saveAndFlush(
				new User(normalizedEmail, passwords.hash(temporaryPassword), fullName, roles, clock.instant()));
		audit.record(AuditEntry.of(AuditAction.USER_CREATED)
			.target("USER", user.getId())
			.detail("email", user.getEmail())
			.detail("roles", sorted(roles)));
		return user;
	}

	/** Partial update: {@code null} arguments are left unchanged. */
	@Transactional
	public User update(AuthenticatedUser actor, UUID userId, String fullName, Set<Role> roles, Boolean enabled) {
		User user = get(userId);
		boolean removesAdmin = user.hasRole(Role.ADMIN)
				&& ((roles != null && !roles.contains(Role.ADMIN)) || Boolean.FALSE.equals(enabled));
		if (removesAdmin && actor.id().equals(userId)) {
			throw ApiException.conflict(ErrorCode.OPERATION_NOT_ALLOWED,
					"You cannot remove your own administrator access or disable your own account");
		}
		if (removesAdmin && user.isEnabled() && users.countEnabledWithRole(Role.ADMIN) <= 1) {
			throw ApiException.conflict(ErrorCode.LAST_ADMIN, "At least one enabled administrator must remain");
		}

		Map<String, Object> changes = new LinkedHashMap<>();
		if (fullName != null && !fullName.strip().equals(user.getFullName())) {
			changes.put("fullName", Map.of("from", user.getFullName(), "to", fullName.strip()));
			user.rename(fullName);
		}
		if (roles != null && !roles.equals(user.getRoles())) {
			changes.put("roles", Map.of("from", sorted(user.getRoles()), "to", sorted(roles)));
			user.replaceRoles(roles);
		}
		boolean disabling = Boolean.FALSE.equals(enabled) && user.isEnabled();
		if (enabled != null && enabled != user.isEnabled()) {
			changes.put("enabled", Map.of("from", user.isEnabled(), "to", enabled));
			user.setEnabled(enabled);
		}
		if (changes.isEmpty()) {
			return user;
		}
		users.flush();
		if (disabling) {
			changes.put("revokedSessions", sessions.revokeAll(userId, RevocationReason.ACCOUNT_DISABLED));
		}
		AuditEntry entry = AuditEntry.of(AuditAction.USER_UPDATED).target("USER", userId);
		changes.forEach(entry::detail);
		audit.record(entry);
		return get(userId);
	}

	@Transactional
	public User unlock(UUID userId) {
		User user = get(userId);
		user.unlock();
		audit.record(AuditEntry.of(AuditAction.USER_UNLOCKED).target("USER", userId));
		return user;
	}

	@Transactional
	public int revokeSessions(UUID userId) {
		get(userId);
		int revoked = sessions.revokeAll(userId, RevocationReason.ADMIN_REVOKED);
		audit.record(AuditEntry.of(AuditAction.USER_SESSIONS_REVOKED)
			.target("USER", userId)
			.detail("revokedSessions", revoked));
		return revoked;
	}

	private static List<String> sorted(Set<Role> roles) {
		return roles.stream().map(Role::name).sorted().toList();
	}

}
