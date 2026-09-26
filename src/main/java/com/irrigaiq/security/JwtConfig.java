package com.irrigaiq.security;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/**
 * RS256 signing keys for access tokens. Asymmetric keys let other services (the ML service, API
 * gateways) verify tokens through {@code /.well-known/jwks.json} without holding a shared secret.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

	private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

	@Bean
	public RSAKey jwtSigningKey(SecurityProperties properties, ResourceLoader resourceLoader) {
		SecurityProperties.Jwt jwt = properties.jwt();
		if (StringUtils.hasText(jwt.privateKeyLocation()) && StringUtils.hasText(jwt.publicKeyLocation())) {
			RSAPrivateKey privateKey = readKey(resourceLoader, jwt.privateKeyLocation(), RsaKeyConverters.pkcs8());
			RSAPublicKey publicKey = readKey(resourceLoader, jwt.publicKeyLocation(), RsaKeyConverters.x509());
			return rsaKey(publicKey, privateKey, jwt.keyId());
		}
		if (!jwt.allowEphemeralKeys()) {
			throw new IllegalStateException("JWT signing keys are not configured. Set JWT_PRIVATE_KEY_LOCATION and "
					+ "JWT_PUBLIC_KEY_LOCATION (see scripts/generate-jwt-keys.sh).");
		}
		log.warn("No JWT signing keys configured: generated an ephemeral RSA key pair. Access tokens will not "
				+ "survive a restart. This is only acceptable for local development.");
		KeyPair keyPair = generateRsaKeyPair();
		return rsaKey((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate(),
				jwt.keyId() + "-ephemeral-" + UUID.randomUUID().toString().substring(0, 8));
	}

	@Bean
	public JwtEncoder jwtEncoder(RSAKey jwtSigningKey) {
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtSigningKey)));
	}

	@Bean
	public JwtDecoder jwtDecoder(RSAKey jwtSigningKey, SecurityProperties properties) throws JOSEException {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey())
			.signatureAlgorithm(SignatureAlgorithm.RS256)
			.build();
		String audience = properties.jwt().audience();
		OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
				new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, (aud) -> aud != null && aud.contains(audience)));
		decoder.setJwtValidator(validator);
		return decoder;
	}

	private static RSAKey rsaKey(RSAPublicKey publicKey, RSAPrivateKey privateKey, String keyId) {
		return new RSAKey.Builder(publicKey).privateKey(privateKey)
			.keyID(keyId)
			.keyUse(KeyUse.SIGNATURE)
			.algorithm(JWSAlgorithm.RS256)
			.build();
	}

	private static <K> K readKey(ResourceLoader resourceLoader, String location, Converter<InputStream, K> converter) {
		Resource resource = resourceLoader.getResource(location);
		try (InputStream inputStream = resource.getInputStream()) {
			return converter.convert(inputStream);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to read JWT key from " + location, ex);
		}
	}

	private static KeyPair generateRsaKeyPair() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
