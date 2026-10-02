package com.irrigaiq.telemetry;

import java.time.Instant;

/**
 * One timestamped set of measurements from a device; any metric may be absent.
 *
 * @param soilMoisturePct probe reading, percent on the probe's calibrated scale
 * @param flowLpm instantaneous flow, litres per minute
 * @param volumeLitres litres since the device's previous reading (pulse-counting meters)
 * @param vibrationMmS vibration velocity, mm/s RMS
 */
public record Reading(Instant recordedAt, Double soilMoisturePct, Double temperatureC, Double humidityPct,
		Double rainfallMm, Double flowLpm, Double volumeLitres, Double pressureBar, Double powerKw,
		Double vibrationMmS, Double motorTempC) {

	public boolean isEmpty() {
		return soilMoisturePct == null && temperatureC == null && humidityPct == null && rainfallMm == null
				&& flowLpm == null && volumeLitres == null && pressureBar == null && powerKw == null
				&& vibrationMmS == null && motorTempC == null;
	}

}
