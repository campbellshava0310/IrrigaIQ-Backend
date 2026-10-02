package com.irrigaiq.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Platform roles. Kept deliberately coarse for the MVP; farm-level membership (operators,
 * agronomists, read-only stakeholders) will layer on top without changing these.
 */
public enum Role {

	/** Platform administration: users, roles, audit trail, system health. */
	ADMIN,

	/** Manages their own farms, fields, crops and irrigation. Default for new accounts. */
	FARM_MANAGER;

	public String authority() {
		return "ROLE_" + name();
	}

	/** Parses role names from a token, ignoring values this build does not know about. */
	public static Set<Role> parseAll(Collection<String> names) {
		Set<Role> roles = EnumSet.noneOf(Role.class);
		if (names == null) {
			return roles;
		}
		for (String name : names) {
			for (Role role : values()) {
				if (role.name().equals(name)) {
					roles.add(role);
				}
			}
		}
		return roles;
	}

}
