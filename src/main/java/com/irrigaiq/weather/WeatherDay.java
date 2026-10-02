package com.irrigaiq.weather;

import java.time.LocalDate;

/**
 * One day of weather at a farm.
 *
 * @param windMaxKmh maximum wind speed at 10 m, km/h
 * @param forecast whether the day has not fully happened yet (today counts as forecast)
 */
public record WeatherDay(LocalDate date, Double et0Mm, Double precipitationMm, Integer precipitationProbability,
		Double tempMaxC, Double tempMinC, Double windMaxKmh, WeatherSource source, boolean forecast) {

	WeatherDay withForecast(boolean value) {
		return new WeatherDay(date, et0Mm, precipitationMm, precipitationProbability, tempMaxC, tempMinC, windMaxKmh,
				source, value);
	}

}
