package com.irrigaiq.telemetry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Device keys: {@code iqd_<prefix>_<secret>}. The 10-character prefix identifies the device (and is
 * safe to display); the 256-bit secret authenticates it. Only a SHA-256 hash of the whole key is
 * stored: the key has full entropy, so a slow password hash would add nothing.
 */
final class DeviceKeys {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final String PREFIX_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

	private static final Pattern FORMAT = Pattern.compile("^iqd_([a-z2-9]{10})_([A-Za-z0-9_-]{43})$");

	/** A newly issued key: the value is shown to the user once and never stored. */
	record Issued(String value, String prefix, String hash) {
	}

	private DeviceKeys() {
	}

	static Issued issue() {
		StringBuilder prefix = new StringBuilder(10);
		for (int i = 0; i < 10; i++) {
			prefix.append(PREFIX_ALPHABET.charAt(RANDOM.nextInt(PREFIX_ALPHABET.length())));
		}
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		String value = "iqd_" + prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
		return new Issued(value, prefix.toString(), hash(value));
	}

	/** The lookup prefix of a well-formed key. */
	static Optional<String> prefixOf(String key) {
		if (key == null) {
			return Optional.empty();
		}
		Matcher matcher = FORMAT.matcher(key.strip());
		return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
	}

	/** Constant-time comparison of a presented key with a stored hash. */
	static boolean matches(String key, String storedHash) {
		byte[] presented = hash(key.strip()).getBytes(StandardCharsets.US_ASCII);
		byte[] expected = (storedHash != null) ? storedHash.getBytes(StandardCharsets.US_ASCII) : new byte[64];
		return MessageDigest.isEqual(presented, expected) && storedHash != null;
	}

	static String hash(String key) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

}
