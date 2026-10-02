package com.irrigaiq.maintenance;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.irrigaiq.assets.OpenWorkOrderCounter;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tells the health engine how much corrective work is outstanding per asset. */
@Component
class OpenWorkOrderAdapter implements OpenWorkOrderCounter {

	private final WorkOrderRepository workOrders;

	OpenWorkOrderAdapter(WorkOrderRepository workOrders) {
		this.workOrders = workOrders;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Integer> openCorrectiveWorkOrders(UUID farmId) {
		return workOrders.countByAsset(farmId, WorkOrderStatus.ACTIVE)
			.stream()
			.collect(Collectors.toMap(WorkOrderRepository.AssetCount::getAssetId, (row) -> (int) row.getTotal()));
	}

}
