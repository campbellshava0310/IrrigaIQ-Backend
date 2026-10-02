package com.irrigaiq.ml;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection to the IrrigaIQ ML service (IrrigaIQ-ML repository).
 *
 * @param enabled {@code false} runs the platform on its rules and water balance alone
 * @param token shared service token, sent as {@code X-Service-Token}; empty when the service has none
 * @param timeout per-call budget: model inference is milliseconds, so anything slower is a fault
 * @param retryAfterFailure how long to stop calling after a failure (a simple circuit breaker)
 */
@ConfigurationProperties("irrigaiq.ml")
public record MlProperties(@DefaultValue("true") boolean enabled,
		@DefaultValue("http://localhost:8090") String baseUrl, @DefaultValue("") String token,
		@DefaultValue("3s") Duration timeout, @DefaultValue("30s") Duration retryAfterFailure) {
}
