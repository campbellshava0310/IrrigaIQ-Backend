package com.irrigaiq.security;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("irrigaiq.security")
public record SecurityProperties(@DefaultValue("true") boolean registrationEnabled, @Valid @NotNull Jwt jwt,
		@Valid @NotNull RefreshToken refreshToken, @Valid @NotNull Lockout lockout, @Valid @NotNull Cors cors) {

	/**
	 * @param privateKeyLocation PKCS#8 PEM resource (e.g. {@code file:/run/secrets/jwt-private.pem})
	 * @param publicKeyLocation X.509 PEM resource matching the private key
	 * @param allowEphemeralKeys generate a throwaway key pair when none is configured (local dev only)
	 */
	public record Jwt(@NotBlank String issuer, @NotBlank String audience, @NotNull Duration accessTokenTtl,
			@NotBlank String keyId, String privateKeyLocation, String publicKeyLocation,
			boolean allowEphemeralKeys) {
	}

	/**
	 * @param reuseLeeway window in which re-presenting a just-rotated token is treated as a benign
	 * client race rather than theft
	 */
	public record RefreshToken(@NotNull Duration ttl, @NotNull Duration reuseLeeway, @NotBlank String cookieName,
			@NotBlank String cookiePath, boolean cookieSecure, @NotBlank String cookieSameSite,
			String cookieDomain) {
	}

	public record Lockout(@Min(1) int maxFailedAttempts, @NotNull Duration duration) {
	}

	public record Cors(@DefaultValue List<String> allowedOrigins) {
	}

}
