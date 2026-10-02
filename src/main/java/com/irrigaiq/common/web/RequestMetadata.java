package com.irrigaiq.common.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Client network metadata used for sessions and audit events. The remote address is already
 * proxy-aware when {@code server.forward-headers-strategy} is configured (prod profile).
 */
public record RequestMetadata(String ipAddress, String userAgent) {

	private static final int MAX_USER_AGENT_LENGTH = 512;

	private static final RequestMetadata NONE = new RequestMetadata(null, null);

	public static RequestMetadata from(HttpServletRequest request) {
		return new RequestMetadata(request.getRemoteAddr(), truncate(request.getHeader(HttpHeaders.USER_AGENT)));
	}

	/** Metadata of the request bound to the current thread, or empty values outside a request. */
	public static RequestMetadata current() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes instanceof ServletRequestAttributes servletAttributes) {
			return from(servletAttributes.getRequest());
		}
		return NONE;
	}

	private static String truncate(String value) {
		if (value == null || value.length() <= MAX_USER_AGENT_LENGTH) {
			return value;
		}
		return value.substring(0, MAX_USER_AGENT_LENGTH);
	}

}
