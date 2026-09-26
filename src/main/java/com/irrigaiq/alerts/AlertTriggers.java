package com.irrigaiq.alerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.irrigaiq.common.events.FarmDataChanged;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Re-evaluates alerts right after any farm change is committed, and periodically for everything
 * that changes with time alone (soil drying out, weather, service intervals running out).
 */
@Component
class AlertTriggers {

	private static final Logger log = LoggerFactory.getLogger(AlertTriggers.class);

	private final AlertEvaluator evaluator;

	private final FarmRepository farms;

	AlertTriggers(AlertEvaluator evaluator, FarmRepository farms) {
		this.evaluator = evaluator;
		this.farms = farms;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onFarmDataChanged(FarmDataChanged event) {
		try {
			evaluator.evaluate(event.farmId());
		}
		catch (RuntimeException ex) {
			// The user's change is already committed; alerts catch up on the next sweep.
			log.warn("Alert evaluation failed for farm {}", event.farmId(), ex);
		}
	}

	@Scheduled(initialDelayString = "${irrigaiq.alerts.initial-delay:PT45S}",
			fixedDelayString = "${irrigaiq.alerts.evaluation-interval:PT15M}")
	void sweep() {
		for (Farm farm : farms.findAll()) {
			try {
				evaluator.evaluate(farm.getId());
			}
			catch (RuntimeException ex) {
				log.warn("Scheduled alert evaluation failed for farm {}", farm.getId(), ex);
			}
		}
	}

}
