package com.irrigaiq.identity;

/**
 * How a client holds its refresh token. {@code WEB}: HttpOnly cookie, never visible to JavaScript.
 * {@code MOBILE}: returned in the response body for storage in the platform keystore.
 */
public enum ClientType {

	WEB, MOBILE;

	/** Resolves the {@code X-Client-Type} header; anything other than "web" is a token-in-body client. */
	public static ClientType fromHeader(String value) {
		return "web".equalsIgnoreCase(value) ? WEB : MOBILE;
	}

}
