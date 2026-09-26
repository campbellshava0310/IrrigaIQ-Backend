package com.irrigaiq.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.common.error.ProblemDetails;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Renders filter-chain security failures in the same problem format as the rest of the API, while
 * keeping the RFC 6750 {@code WWW-Authenticate} header that bearer-token clients rely on.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();

	private final BearerTokenAccessDeniedHandler bearerAccessDeniedHandler = new BearerTokenAccessDeniedHandler();

	private final ObjectMapper objectMapper;

	public SecurityErrorHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		bearerEntryPoint.commence(request, response, exception);
		boolean badToken = exception instanceof InvalidBearerTokenException;
		write(request, response, HttpStatus.UNAUTHORIZED, badToken ? ErrorCode.TOKEN_INVALID : ErrorCode.UNAUTHORIZED,
				badToken ? "The access token is invalid or has expired" : "Authentication is required");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
			throws IOException {
		bearerAccessDeniedHandler.handle(request, response, exception);
		write(request, response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "You do not have permission to perform this action");
	}

	private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, ErrorCode code,
			String detail) throws IOException {
		ProblemDetail problem = ProblemDetails.of(status, code, detail);
		problem.setInstance(URI.create(request.getRequestURI()));
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), ProblemDetails.toMap(problem));
	}

}
