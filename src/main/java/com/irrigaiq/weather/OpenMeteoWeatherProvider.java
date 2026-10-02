package com.irrigaiq.weather;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Open-Meteo daily forecast API: FAO-56 reference evapotranspiration, rainfall, rain probability
 * and temperature for up to 92 past and 16 forecast days. No API key for non-commercial use;
 * commercial deployments need an Open-Meteo subscription key.
 */
@Component
class OpenMeteoWeatherProvider implements WeatherProvider {

	private static final String DAILY = "et0_fao_evapotranspiration,precipitation_sum,precipitation_probability_max,"
			+ "temperature_2m_max,temperature_2m_min,wind_speed_10m_max";

	private final RestClient client;

	OpenMeteoWeatherProvider(RestClient.Builder builder, WeatherProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.timeout());
		requestFactory.setReadTimeout(properties.timeout());
		this.client = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
	}

	@Override
	public List<WeatherDay> fetch(double latitude, double longitude, String timezone, int pastDays, int forecastDays) {
		Response response = client.get()
			.uri((uri) -> uri.path("/v1/forecast")
				.queryParam("latitude", latitude)
				.queryParam("longitude", longitude)
				.queryParam("daily", DAILY)
				.queryParam("timezone", timezone)
				.queryParam("past_days", pastDays)
				.queryParam("forecast_days", forecastDays)
				.build())
			.retrieve()
			.body(Response.class);
		if (response == null || response.daily() == null || response.daily().time() == null) {
			throw new IllegalStateException("Open-Meteo returned no daily data");
		}
		Daily daily = response.daily();
		List<WeatherDay> days = new ArrayList<>(daily.time().size());
		for (int i = 0; i < daily.time().size(); i++) {
			days.add(new WeatherDay(LocalDate.parse(daily.time().get(i)), at(daily.et0(), i),
					at(daily.precipitation(), i), at(daily.precipitationProbability(), i), at(daily.tempMax(), i),
					at(daily.tempMin(), i), at(daily.windMax(), i), WeatherSource.OPEN_METEO, false));
		}
		return days;
	}

	private static <T> T at(List<T> values, int index) {
		return (values != null && index < values.size()) ? values.get(index) : null;
	}

	record Response(Daily daily) {
	}

	record Daily(List<String> time, @JsonProperty("et0_fao_evapotranspiration") List<Double> et0,
			@JsonProperty("precipitation_sum") List<Double> precipitation,
			@JsonProperty("precipitation_probability_max") List<Integer> precipitationProbability,
			@JsonProperty("temperature_2m_max") List<Double> tempMax,
			@JsonProperty("temperature_2m_min") List<Double> tempMin,
			@JsonProperty("wind_speed_10m_max") List<Double> windMax) {
	}

}
