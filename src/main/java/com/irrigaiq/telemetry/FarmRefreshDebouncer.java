package com.irrigaiq.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.irrigaiq.common.events.FarmDataChanged;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Coalesces telemetry into at most one farm re-evaluation (alerts, anomaly checks) every few
 * seconds. Devices report every minute or faster; re-running every rule per request would make
 * ingestion as slow as the slowest rule, so the refresh trails the burst by a short delay.
 */
@Component
class FarmRefreshDebouncer {

	static final Duration DELAY = Duration.ofSeconds(3);

	private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

	private final TaskScheduler scheduler;

	private final ApplicationEventPublisher publisher;

	private final Clock clock;

	FarmRefreshDebouncer(TaskScheduler scheduler, ApplicationEventPublisher publisher, Clock clock) {
		this.scheduler = scheduler;
		this.publisher = publisher;
		this.clock = clock;
	}

	/** Schedules a refresh once the caller's transaction commits (nothing happens if it rolls back). */
	void request(UUID farmId) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					schedule(farmId);
				}
			});
		}
		else {
			schedule(farmId);
		}
	}

	private void schedule(UUID farmId) {
		if (pending.add(farmId)) {
			scheduler.schedule(() -> {
				pending.remove(farmId);
				publisher.publishEvent(new FarmDataChanged(farmId));
			}, clock.instant().plus(DELAY));
		}
	}

}
