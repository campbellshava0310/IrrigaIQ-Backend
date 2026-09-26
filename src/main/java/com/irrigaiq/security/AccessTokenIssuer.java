package com.irrigaiq.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nimbusds.jose.jwk.RSAKey;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Mints short-lived access tokens. They are self-contained (no per-request database lookup), so
 * their lifetime bounds how long a revoked session or role change can linger; keep it short.
 */
@Component
public class AccessTokenIssuer {

	private final JwtEncoder encoder;

	private final SecurityProperties.Jwt properties;

	private final String keyId;

	private final Clock clock;

	public AccessTokenIssuer(JwtEncoder encoder, SecurityProperties properties, RSAKey jwtSigningKey, Clock clock) {
		this.encoder = encoder;
		this.properties = properties.jwt();
		this.keyId = jwtSigningKey.getKeyID();
		this.clock = clock;
	}

	public IssuedAccessToken issue(UUID userId, String email, String fullName, Set<Role> roles, UUID sessionId) {
		Instant issuedAt = clock.instant();
		Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(properties.issuer())
			.audience(List.of(properties.audience()))
			.subject(userId.toString())
			.id(UUID.randomUUID().toString())
			.issuedAt(issuedAt)
			.expiresAt(expiresAt)
			.claim(AccessTokenClaims.EMAIL, email)
			.claim(AccessTokenClaims.NAME, fullName)
			.claim(AccessTokenClaims.ROLES, roles.stream().map(Role::name).sorted().toList())
			.claim(AccessTokenClaims.SESSION_ID, sessionId.toString())
			.build();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).type("JWT").build();
		String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedAccessToken(value, expiresAt);
	}

	public record IssuedAccessToken(String value, Instant expiresAt) {
	}

}
