package com.irrigaiq.telemetry;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceKeysTest {

	@Test
	void issuedKeysAreWellFormedUniqueAndOnlyTheirHashIsKept() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			DeviceKeys.Issued key = DeviceKeys.issue();
			assertThat(key.value()).startsWith("iqd_" + key.prefix() + "_");
			assertThat(DeviceKeys.prefixOf(key.value())).contains(key.prefix());
			assertThat(key.hash()).hasSize(64).doesNotContain(key.value());
			assertThat(DeviceKeys.matches(key.value(), key.hash())).isTrue();
			assertThat(seen.add(key.prefix())).isTrue();
		}
	}

	@Test
	void wrongOrMalformedKeysDoNotMatch() {
		DeviceKeys.Issued key = DeviceKeys.issue();
		DeviceKeys.Issued other = DeviceKeys.issue();
		assertThat(DeviceKeys.matches(other.value(), key.hash())).isFalse();
		assertThat(DeviceKeys.matches(key.value(), null)).isFalse();
		assertThat(DeviceKeys.prefixOf("Bearer abc")).isEmpty();
		assertThat(DeviceKeys.prefixOf(null)).isEmpty();
		assertThat(DeviceKeys.prefixOf(key.value() + "x")).isEmpty();
	}

}
