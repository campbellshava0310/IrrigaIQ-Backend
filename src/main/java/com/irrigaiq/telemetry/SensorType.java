package com.irrigaiq.telemetry;

/** What a device measures, which decides where it attaches and which of its readings are used. */
public enum SensorType {

	/** Soil probe in a field: its readings calibrate the field's water balance. */
	SOIL_MOISTURE,

	/** On-farm weather station. */
	WEATHER_STATION,

	/** Pump monitor: power, flow, pressure, vibration and motor temperature for failure-risk scoring. */
	PUMP_MONITOR,

	/** Water meter on a pump, mainline or field: hourly volumes for anomaly (leak) detection. */
	FLOW_METER

}
