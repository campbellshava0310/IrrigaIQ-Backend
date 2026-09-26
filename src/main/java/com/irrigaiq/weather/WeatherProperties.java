package com.irrigaiq.weather;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param provider {@code OPEN_METEO} (live) or {@code CLIMATOLOGY} (offline, deterministic)
 * @param refreshInterval how long cached forecasts stay fresh
 * @param retryAfterFailure back-off before calling a failing provider again
 */
@ConfigurationProperties("irrigaiq.weather")
public record WeatherProperties(@DefaultValue("OPEN_METEO") WeatherSource provider,
		@DefaultValue("https://api.open-meteo.com") String baseUrl, @DefaultValue("3h") Duration refreshInterval,
		@DefaultValue("10m") Duration retryAfterFailure, @DefaultValue("6s") Duration timeout) {
}
