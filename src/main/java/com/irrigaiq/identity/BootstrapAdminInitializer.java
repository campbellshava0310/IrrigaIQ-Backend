package com.irrigaiq.identity;

import java.time.Clock;
import java.util.EnumSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.security.Role;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Guarantees a fresh installation can be administered without manual SQL. */
@Component
class BootstrapAdminInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

	private final BootstrapAdminProperties properties;

	private final UserRepository users;

	private final PasswordHasher passwords;

	private final AuditService audit;

	private final Clock clock;

	BootstrapAdminInitializer(BootstrapAdminProperties properties, UserRepository users, PasswordHasher passwords,
			AuditService audit, Clock clock) {
		this.properties = properties;
		this.users = users;
		this.passwords = passwords;
		this.audit = audit;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!StringUtils.hasText(properties.email()) || !StringUtils.hasText(properties.password())) {
			return;
		}
		if (users.countWithRole(Role.ADMIN) > 0) {
			return;
		}
		String email = EmailAddresses.normalize(properties.email());
		if (users.existsByEmail(email)) {
			log.warn("Bootstrap admin {} already exists without the ADMIN role; not modifying it", email);
			return;
		}
		if (!new StrongPasswordValidator().isValid(properties.password(), null)) {
			throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD does not meet the password policy (12-72 characters)");
		}
		User admin = users.save(new User(email, passwords.hash(properties.password()), properties.fullName(),
				EnumSet.of(Role.ADMIN, Role.FARM_MANAGER), clock.instant()));
		audit.record(AuditEntry.of(AuditAction.USER_CREATED)
			.actor(null, "system")
			.target("USER", admin.getId())
			.detail("source", "bootstrap"));
		log.info("Created bootstrap administrator {}", email);
	}

}
