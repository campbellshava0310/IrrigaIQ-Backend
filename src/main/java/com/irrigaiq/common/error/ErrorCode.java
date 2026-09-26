package com.irrigaiq.common.error;

import org.springframework.http.HttpStatusCode;

/**
 * Machine-readable error codes returned in the {@code code} member of every problem response.
 * Clients branch on these, never on human-readable messages. Treat values as a public contract.
 */
public enum ErrorCode {

	// Generic
	BAD_REQUEST, VALIDATION_FAILED, NOT_FOUND, METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE, CONFLICT,
	CONCURRENT_MODIFICATION, OPERATION_NOT_ALLOWED, INTERNAL_ERROR,

	// Authentication & authorisation
	UNAUTHORIZED, TOKEN_INVALID, FORBIDDEN, INVALID_CREDENTIALS, ACCOUNT_LOCKED, ACCOUNT_DISABLED,
	REFRESH_TOKEN_INVALID, REGISTRATION_DISABLED,

	// Identity
	EMAIL_ALREADY_REGISTERED, CURRENT_PASSWORD_INCORRECT, PASSWORD_REUSE, LAST_ADMIN;

	public static ErrorCode fromStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> BAD_REQUEST;
			case 401 -> UNAUTHORIZED;
			case 403 -> FORBIDDEN;
			case 404 -> NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 409 -> CONFLICT;
			case 415 -> UNSUPPORTED_MEDIA_TYPE;
			default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL_ERROR;
		};
	}

}
