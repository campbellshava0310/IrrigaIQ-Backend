package com.irrigaiq.identity;

import java.util.Locale;

/** Canonical email form used for storage and lookup (the database enforces the same rule). */
public final class EmailAddresses {

	private EmailAddresses() {
	}

	public static String normalize(String email) {
		return (email != null) ? email.strip().toLowerCase(Locale.ROOT) : null;
	}

}
