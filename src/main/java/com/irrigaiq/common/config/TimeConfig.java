package com.irrigaiq.common.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single source of "now" for the whole application, so time-dependent rules (token expiry,
 * lockouts, auditing) can be tested deterministically.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
