package com.irrigaiq.weather;

import java.util.List;

/** A remote source of daily weather (past and forecast) for a coordinate. */
public interface WeatherProvider {

	List<WeatherDay> fetch(double latitude, double longitude, String timezone, int pastDays, int forecastDays);

}
