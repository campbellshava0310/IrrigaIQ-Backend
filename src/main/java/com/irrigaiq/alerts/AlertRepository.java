package com.irrigaiq.alerts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

	List<Alert> findByFarmIdAndResolvedAtIsNull(UUID farmId);

	List<Alert> findByFarmIdAndResolvedAtIsNotNullOrderByResolvedAtDesc(UUID farmId, Pageable page);

	Optional<Alert> findByIdAndFarmId(UUID id, UUID farmId);

	/** A person resolved this condition recently: respect that and do not re-raise it straight away. */
	@Query("""
			select count(a) > 0 from Alert a
			where a.farm.id = :farmId and a.dedupeKey = :dedupeKey and a.resolvedBy is not null and a.resolvedAt > :since
			""")
	boolean recentlyDismissed(UUID farmId, String dedupeKey, Instant since);

}
