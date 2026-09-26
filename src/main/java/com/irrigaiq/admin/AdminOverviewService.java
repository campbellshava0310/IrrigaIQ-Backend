package com.irrigaiq.admin;

import java.time.Clock;

import com.irrigaiq.farm.CropRepository;
import com.irrigaiq.farm.FarmRepository;
import com.irrigaiq.farm.FieldRepository;
import com.irrigaiq.identity.SessionService;
import com.irrigaiq.identity.UserRepository;
import com.irrigaiq.security.Role;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-wide counters for the admin console. */
@Service
public class AdminOverviewService {

	private final UserRepository users;

	private final SessionService sessions;

	private final FarmRepository farms;

	private final FieldRepository fields;

	private final CropRepository crops;

	private final Clock clock;

	public AdminOverviewService(UserRepository users, SessionService sessions, FarmRepository farms,
			FieldRepository fields, CropRepository crops, Clock clock) {
		this.users = users;
		this.sessions = sessions;
		this.farms = farms;
		this.fields = fields;
		this.crops = crops;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Overview overview() {
		UserStats userStats = new UserStats(users.count(), users.countByEnabledTrue(),
				users.countLockedAt(clock.instant()), users.countWithRole(Role.ADMIN));
		FarmStats farmStats = new FarmStats(farms.count(), fields.count(), crops.count());
		return new Overview(userStats, sessions.countActive(), farmStats);
	}

	public record Overview(UserStats users, long activeSessions, FarmStats farms) {
	}

	public record UserStats(long total, long enabled, long locked, long admins) {
	}

	public record FarmStats(long farms, long fields, long crops) {
	}

}
