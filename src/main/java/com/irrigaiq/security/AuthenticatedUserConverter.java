package com.irrigaiq.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Maps a verified access token onto an {@link AuthenticatedUser} with role authorities. */
final class AuthenticatedUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		Set<Role> roles = Role.parseAll(jwt.getClaimAsStringList(AccessTokenClaims.ROLES));
		List<GrantedAuthority> authorities = roles.stream()
			.<GrantedAuthority>map((role) -> new SimpleGrantedAuthority(role.authority()))
			.toList();
		String sessionId = jwt.getClaimAsString(AccessTokenClaims.SESSION_ID);
		AuthenticatedUser principal = new AuthenticatedUser(UUID.fromString(jwt.getSubject()),
				jwt.getClaimAsString(AccessTokenClaims.EMAIL), jwt.getClaimAsString(AccessTokenClaims.NAME), roles,
				(sessionId != null) ? UUID.fromString(sessionId) : null);
		return new AuthenticatedUserToken(principal, jwt, authorities);
	}

}
