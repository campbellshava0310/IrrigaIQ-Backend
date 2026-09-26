package com.irrigaiq.alerts;

public enum AlertType {

	// Irrigation & weather
	IRRIGATION_REQUIRED, CROP_WATER_STRESS, RAIN_EXPECTED,

	// Infrastructure
	SERVICE_OVERDUE, ASSET_FAILURE_RISK, PUMP_EFFICIENCY_DROP, FILTER_CLOGGING, LEAK_REPORTED, ASSET_DOWN,
	WATER_LEVEL_FALLING,

	// Water accounting
	WATER_ALLOCATION,

	// Reserved for sensor and ML pipelines
	WATER_ANOMALY, SENSOR_FAULT;

	public boolean isInfrastructure() {
		return switch (this) {
			case SERVICE_OVERDUE, ASSET_FAILURE_RISK, PUMP_EFFICIENCY_DROP, FILTER_CLOGGING, LEAK_REPORTED, ASSET_DOWN,
					WATER_LEVEL_FALLING ->
				true;
			default -> false;
		};
	}

}
