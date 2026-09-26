package com.irrigaiq.identity.web;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.identity.AuthResult;
import com.irrigaiq.identity.AuthService;
import com.irrigaiq.identity.ClientContext;
import com.irrigaiq.identity.ClientType;
import com.irrigaiq.identity.web.AuthRequests.LoginRequest;
import com.irrigaiq.identity.web.AuthRequests.RefreshRequest;
import com.irrigaiq.identity.web.AuthRequests.RegisterRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints. Web clients identify themselves with {@code X-Client-Type: web}
 * and get their refresh token as an HttpOnly cookie; every other client gets it in the body.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Send `X-Client-Type: web` from browsers to receive the refresh "
		+ "token as an HttpOnly cookie; other clients receive it in the response body.")
@SecurityRequirements
public class AuthController {

	private final AuthService auth;

	private final RefreshTokenCookies cookies;

	private final Clock clock;

	public AuthController(AuthService auth, RefreshTokenCookies cookies, Clock clock) {
		this.auth = auth;
		this.cookies = cookies;
		this.clock = clock;
	}

	@PostMapping("/register")
	@Operation(summary = "Create an account (FARM_MANAGER role) and sign in")
	public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest body,
			HttpServletRequest request) {
		ClientContext client = ClientContext.from(request);
		AuthResult result = auth.register(body.email(), body.password(), body.fullName(), client);
		return respond(HttpStatus.CREATED, result, client);
	}

	@PostMapping("/login")
	@Operation(summary = "Sign in with email and password")
	public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
		ClientContext client = ClientContext.from(request);
		return respond(HttpStatus.OK, auth.login(body.email(), body.password(), client), client);
	}

	@PostMapping("/refresh")
	@Operation(summary = "Exchange a refresh token for a new access token (the refresh token is rotated)")
	public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody(required = false) RefreshRequest body,
			HttpServletRequest request, HttpServletResponse response) {
		ClientContext client = ClientContext.from(request);
		try {
			return respond(HttpStatus.OK, auth.refresh(refreshToken(body, request, client), client), client);
		}
		catch (ApiException ex) {
			if (client.clientType() == ClientType.WEB) {
				response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
			}
			throw ex;
		}
	}

	@PostMapping("/logout")
	@Operation(summary = "End the current session (idempotent)")
	public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) RefreshRequest body,
			HttpServletRequest request) {
		ClientContext client = ClientContext.from(request);
		auth.logout(refreshToken(body, request, client));
		ResponseEntity.HeadersBuilder<?> builder = ResponseEntity.noContent();
		if (client.clientType() == ClientType.WEB) {
			builder.header(HttpHeaders.SET_COOKIE, cookies.clear().toString());
		}
		return builder.build();
	}

	private String refreshToken(RefreshRequest body, HttpServletRequest request, ClientContext client) {
		if (client.clientType() == ClientType.WEB) {
			return cookies.read(request).orElse(null);
		}
		return (body != null) ? body.refreshToken() : null;
	}

	private ResponseEntity<AuthResponse> respond(HttpStatus status, AuthResult result, ClientContext client) {
		boolean web = client.clientType() == ClientType.WEB;
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
		if (web) {
			builder.header(HttpHeaders.SET_COOKIE,
					cookies.issue(result.refreshToken(), result.refreshTokenExpiresAt()).toString());
		}
		return builder.body(AuthResponse.from(result, clock.instant(), !web));
	}

}
