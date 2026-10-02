package com.irrigaiq.telemetry;

public enum DeviceStatus {

	ACTIVE,

	/** Rejects readings; its key stays valid so it can be re-enabled without reflashing. */
	DISABLED

}
