package com.irrigaiq.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Authentication backed by a verified JWT whose principal is an {@link AuthenticatedUser}. */
public final class AuthenticatedUserToken extends AbstractAuthenticationToken {

	private final AuthenticatedUser principal;

	private final transient Jwt token;

	public AuthenticatedUserToken(AuthenticatedUser principal, Jwt token,
			Collection<? extends GrantedAuthority> authorities) {
		super(authorities);
		this.principal = principal;
		this.token = token;
		setAuthenticated(true);
	}

	@Override
	public AuthenticatedUser getPrincipal() {
		return principal;
	}

	@Override
	public Jwt getCredentials() {
		return token;
	}

	@Override
	public String getName() {
		return principal.id().toString();
	}

}
