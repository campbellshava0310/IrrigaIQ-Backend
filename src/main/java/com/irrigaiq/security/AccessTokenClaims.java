package com.irrigaiq.security;

/** Custom claims carried by IrrigaIQ access tokens, alongside the registered JWT claims. */
public final class AccessTokenClaims {

	public static final String EMAIL = "email";

	public static final String NAME = "name";

	public static final String ROLES = "roles";

	/** Refresh-token session id; lets the API identify "this device" for session management. */
	public static final String SESSION_ID = "sid";

	private AccessTokenClaims() {
	}

}
