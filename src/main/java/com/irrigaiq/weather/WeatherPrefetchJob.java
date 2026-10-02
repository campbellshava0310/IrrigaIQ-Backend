package com.irrigaiq.weather;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every farm's forecast warm so user requests almost never wait on the weather provider.
 */
@Component
@ConditionalOnProperty(name = "irrigaiq.weather.provider", havingValue = "OPEN_METEO", matchIfMissing = true)
class WeatherPrefetchJob {

	private static final Logger log = LoggerFactory.getLogger(WeatherPrefetchJob.class);

	private final FarmRepository farms;

	private final WeatherService weather;

	WeatherPrefetchJob(FarmRepository farms, WeatherService weather) {
		this.farms = farms;
		this.weather = weather;
	}

	@Scheduled(initialDelayString = "PT20S", fixedDelayString = "${irrigaiq.weather.prefetch-interval:PT1H}")
	void prefetchAll() {
		for (Farm farm : farms.findAll()) {
			try {
				weather.prefetch(farm);
			}
			catch (RuntimeException ex) {
				log.warn("Weather prefetch failed for farm {}", farm.getId(), ex);
			}
		}
	}

}
