package com.irrigaiq.identity;

public enum RevocationReason {

	/** Superseded by its successor during a normal refresh. */
	ROTATED,

	LOGOUT,

	/** A rotated token was presented again: the session is assumed compromised. */
	REUSE_DETECTED,

	PASSWORD_CHANGED,

	/** Revoked by the user from their session list. */
	USER_REVOKED,

	ADMIN_REVOKED,

	ACCOUNT_DISABLED

}
