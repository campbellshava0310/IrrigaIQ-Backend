package com.irrigaiq.weather;

import java.time.LocalDate;
import java.util.SplittableRandom;

/**
 * Deterministic seasonal weather for the tropics/sub-tropics, tuned to the Zimbabwean highveld:
 * reference ET about 6 mm/day at the hot dry peak (October) and about 3 mm/day in winter, with
 * rain from November to March. The same coordinates and date always produce the same values, so
 * the offline demo and tests are reproducible. Wind peaks in the dry, gusty spring (September).
 */
final class Climatology {

	private Climatology() {
	}

	static WeatherDay day(double latitude, double longitude, LocalDate date, boolean forecast) {
		double hemisphereShift = (latitude < 0) ? 0 : 182.6; // seasons are reversed north of the equator
		int doy = date.getDayOfYear();
		double heat = Math.cos(2 * Math.PI * (doy - 290 - hemisphereShift) / 365.25); // +1 in Oct (south)
		double wet = Math.pow(Math.max(0, Math.cos(2 * Math.PI * (doy - 15 - hemisphereShift) / 365.25)), 1.5);
		double rainChance = 0.04 + 0.46 * wet;

		double rain;
		Integer probability = null;
		if (forecast) {
			rain = rainChance * (6 + 14 * wet); // expected value: no fake certainty about future storms
			probability = (int) Math.round(rainChance * 100);
		}
		else {
			SplittableRandom random = new SplittableRandom(seed(latitude, longitude, date));
			rain = (random.nextDouble() < rainChance) ? (2 + 22 * random.nextDouble()) * (0.6 + wet) : 0;
		}
		double et0 = (4.4 + 1.7 * heat) * ((rain > 5) ? 0.7 : 1.0);
		double tempMax = 25 + 4.5 * heat - ((rain > 5) ? 3 : 0);
		double tempMin = tempMax - 13 + 3 * wet;
		double windMax = 12 + 4 * Math.cos(2 * Math.PI * (doy - 255 - hemisphereShift) / 365.25);
		return new WeatherDay(date, round(et0), round(rain), probability, round(tempMax), round(tempMin),
				round(windMax), WeatherSource.CLIMATOLOGY, forecast);
	}

	private static long seed(double latitude, double longitude, LocalDate date) {
		long lat = Math.round(latitude * 100);
		long lon = Math.round(longitude * 100);
		return (date.toEpochDay() * 1_000_003L) ^ (lat * 31_337L) ^ (lon * 7_919L);
	}

	private static double round(double value) {
		return Math.round(value * 10) / 10.0;
	}

}
