package com.irrigaiq.maintenance;

import java.util.Set;

public enum WorkOrderStatus {

	OPEN, SCHEDULED, IN_PROGRESS, DONE, CANCELLED;

	public static final Set<WorkOrderStatus> ACTIVE = Set.of(OPEN, SCHEDULED, IN_PROGRESS);

	public boolean isActive() {
		return ACTIVE.contains(this);
	}

}
