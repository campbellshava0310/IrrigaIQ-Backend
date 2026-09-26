package com.irrigaiq.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * An expected, client-facing failure. Rendered as an RFC 9457 problem response with a stable
 * {@link ErrorCode}. Messages must be safe to show to end users.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final ErrorCode code;

	private final Map<String, Object> properties;

	public ApiException(HttpStatus status, ErrorCode code, String message) {
		this(status, code, message, Map.of());
	}

	public ApiException(HttpStatus status, ErrorCode code, String message, Map<String, Object> properties) {
		super(message);
		this.status = status;
		this.code = code;
		this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
	}

	public static ApiException notFound(String resource) {
		return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, resource + " not found");
	}

	public static ApiException conflict(ErrorCode code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message);
	}

	public static ApiException badRequest(ErrorCode code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, message);
	}

	public static ApiException forbidden(ErrorCode code, String message) {
		return new ApiException(HttpStatus.FORBIDDEN, code, message);
	}

	public static ApiException unauthorized(ErrorCode code, String message) {
		return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
	}

	public HttpStatus getStatus() {
		return status;
	}

	public ErrorCode getCode() {
		return code;
	}

	public Map<String, Object> getProperties() {
		return properties;
	}

}
