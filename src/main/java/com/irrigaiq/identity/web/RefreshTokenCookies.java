package com.irrigaiq.identity.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import com.irrigaiq.security.SecurityProperties;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

/**
 * The web client's refresh-token cookie: HttpOnly (unreadable by scripts), SameSite=Strict and
 * scoped to the auth path so it is never sent with ordinary API calls.
 */
@Component
public class RefreshTokenCookies {

	private final SecurityProperties.RefreshToken properties;

	private final Clock clock;

	public RefreshTokenCookies(SecurityProperties properties, Clock clock) {
		this.properties = properties.refreshToken();
		this.clock = clock;
	}

	public ResponseCookie issue(String refreshToken, Instant expiresAt) {
		Duration maxAge = Duration.between(clock.instant(), expiresAt);
		return builder(refreshToken).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build();
	}

	public ResponseCookie clear() {
		return builder("").maxAge(Duration.ZERO).build();
	}

	public Optional<String> read(HttpServletRequest request) {
		Cookie cookie = WebUtils.getCookie(request, properties.cookieName());
		return Optional.ofNullable(cookie).map(Cookie::getValue).filter(StringUtils::hasText);
	}

	private ResponseCookie.ResponseCookieBuilder builder(String value) {
		ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(properties.cookieName(), value)
			.httpOnly(true)
			.secure(properties.cookieSecure())
			.sameSite(properties.cookieSameSite())
			.path(properties.cookiePath());
		if (StringUtils.hasText(properties.cookieDomain())) {
			builder.domain(properties.cookieDomain());
		}
		return builder;
	}

}
