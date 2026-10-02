package com.irrigaiq.ml;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class MlClientTest {

	@Test
	void anUnreachableServiceReturnsEmptyAndOpensTheCircuit() {
		MlClient client = new MlClient(RestClient.builder(),
				new MlProperties(true, "http://127.0.0.1:9", "", Duration.ofMillis(300), Duration.ofMinutes(1)),
				Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC));
		assertThat(client.available()).isTrue();
		assertThat(client.embed(List.of("hello"), true)).isEmpty();
		assertThat(client.available()).isFalse();
		// While open, calls return at once without touching the network.
		assertThat(client.models()).isEmpty();
	}

	@Test
	void aDisabledClientNeverCalls() {
		MlClient client = new MlClient(RestClient.builder(),
				new MlProperties(false, "http://127.0.0.1:9", "", Duration.ofSeconds(1), Duration.ofMinutes(1)),
				Clock.systemUTC());
		assertThat(client.available()).isFalse();
		assertThat(client.predictIrrigation(List.of(new MlApi.FieldFeatures("f", 23, 30, 10, 400, "DEVELOPMENT",
				false)))).isEmpty();
	}

}
