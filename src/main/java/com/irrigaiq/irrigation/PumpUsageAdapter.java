package com.irrigaiq.irrigation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.irrigaiq.assets.PumpUsageProvider;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Supplies pump run-hours to the infrastructure module (dependency inversion: no module cycle). */
@Component
class PumpUsageAdapter implements PumpUsageProvider {

	private final IrrigationEventRepository events;

	PumpUsageAdapter(IrrigationEventRepository events) {
		this.events = events;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Double> runHoursSince(UUID farmId, Instant since) {
		return events.pumpMinutesSince(farmId, since)
			.stream()
			.collect(Collectors.toMap(IrrigationEventRepository.PumpMinutes::getPumpId,
					(row) -> row.getMinutes() / 60.0));
	}

}
