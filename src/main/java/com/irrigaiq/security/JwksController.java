package com.irrigaiq.security;

import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing key so downstream services can verify IrrigaIQ access tokens. */
@RestController
@Tag(name = "Authentication")
public class JwksController {

	private final Map<String, Object> publicJwks;

	public JwksController(RSAKey jwtSigningKey) {
		this.publicJwks = new JWKSet(jwtSigningKey.toPublicJWK()).toJSONObject();
	}

	@GetMapping("/.well-known/jwks.json")
	@Operation(summary = "Public JSON Web Key Set used to verify access tokens")
	public Map<String, Object> jwks() {
		return publicJwks;
	}

}
