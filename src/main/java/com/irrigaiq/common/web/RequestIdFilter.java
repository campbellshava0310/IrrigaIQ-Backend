package com.irrigaiq.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlates every log line, audit event and error response with a request id. A well-formed
 * inbound {@code X-Request-Id} (e.g. from the reverse proxy or a client) is propagated; anything
 * else is replaced so untrusted input never reaches the logs verbatim.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";

	public static final String MDC_KEY = "requestId";

	private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String inbound = request.getHeader(HEADER);
		String requestId = (inbound != null && SAFE_REQUEST_ID.matcher(inbound).matches()) ? inbound
				: UUID.randomUUID().toString();
		MDC.put(MDC_KEY, requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	public static String currentRequestId() {
		return MDC.get(MDC_KEY);
	}

}
