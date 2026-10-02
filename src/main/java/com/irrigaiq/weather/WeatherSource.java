package com.irrigaiq.weather;

public enum WeatherSource {

	/** Open-Meteo forecast/reanalysis (ET0 is FAO-56 Penman-Monteith). */
	OPEN_METEO,

	/** Deterministic seasonal estimate, used when the weather service is unreachable. */
	CLIMATOLOGY

}
