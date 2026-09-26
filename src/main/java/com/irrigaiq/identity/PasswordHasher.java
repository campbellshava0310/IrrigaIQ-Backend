package com.irrigaiq.identity;

import java.nio.charset.StandardCharsets;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Password hashing with the guard rails the raw {@link PasswordEncoder} does not provide. */
@Component
public class PasswordHasher {

	private final PasswordEncoder encoder;

	/** Verified against when the account does not exist, so response time does not reveal that. */
	private final String unknownUserHash;

	public PasswordHasher(PasswordEncoder encoder) {
		this.encoder = encoder;
		this.unknownUserHash = encoder.encode("irrigaiq-timing-equalisation");
	}

	public String hash(String rawPassword) {
		return encoder.encode(rawPassword);
	}

	public boolean matches(String rawPassword, String passwordHash) {
		// bcrypt rejects inputs over 72 bytes; such a password can never match a stored hash.
		if (rawPassword == null || rawPassword.getBytes(StandardCharsets.UTF_8).length > StrongPasswordValidator.MAX_BYTES) {
			return false;
		}
		return encoder.matches(rawPassword, passwordHash);
	}

	public void simulateVerification(String rawPassword) {
		matches(rawPassword, unknownUserHash);
	}

	public boolean needsRehash(String passwordHash) {
		return encoder.upgradeEncoding(passwordHash);
	}

}
