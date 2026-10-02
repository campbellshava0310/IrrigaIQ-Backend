package com.irrigaiq.irrigation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SoilObservationRepository extends JpaRepository<SoilObservation, UUID> {

	@Query("""
			select o from SoilObservation o
			where o.field.farm.id = :farmId and o.observedAt >= :since order by o.observedAt
			""")
	List<SoilObservation> findByFarmSince(UUID farmId, Instant since);

	List<SoilObservation> findByFieldIdOrderByObservedAtDesc(UUID fieldId, Pageable page);

	@Query("""
			select o from SoilObservation o
			where o.field.id = :fieldId and o.method = :method and o.observedAt >= :from and o.observedAt < :to
			order by o.observedAt desc
			""")
	List<SoilObservation> findByFieldAndMethodBetween(UUID fieldId, ObservationMethod method, Instant from,
			Instant to);

}
