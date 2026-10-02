package com.irrigaiq.assets;

import java.util.Map;
import java.util.UUID;

/** Open corrective work per asset; implemented by the maintenance module. */
public interface OpenWorkOrderCounter {

	Map<UUID, Integer> openCorrectiveWorkOrders(UUID farmId);

}
