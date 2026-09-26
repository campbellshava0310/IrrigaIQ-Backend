package com.irrigaiq.irrigation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IrrigationEventRepository extends JpaRepository<IrrigationEvent, UUID> {

	@EntityGraph(attributePaths = "field")
	@Query("""
			select e from IrrigationEvent e
			where e.field.farm.id = :farmId and e.startedAt >= :from and e.startedAt < :to
			order by e.startedAt
			""")
	List<IrrigationEvent> findByFarmBetween(UUID farmId, Instant from, Instant to);

	@EntityGraph(attributePaths = "field")
	@Query("select e from IrrigationEvent e where e.id = :id and e.field.farm.id = :farmId")
	Optional<IrrigationEvent> findInFarm(UUID id, UUID farmId);

	@Query("""
			select e.pumpAssetId as pumpId, sum(e.durationMinutes) as minutes from IrrigationEvent e
			where e.field.farm.id = :farmId and e.status = com.irrigaiq.irrigation.IrrigationStatus.COMPLETED
			  and e.startedAt >= :since and e.pumpAssetId is not null
			group by e.pumpAssetId
			""")
	List<PumpMinutes> pumpMinutesSince(UUID farmId, Instant since);

	interface PumpMinutes {

		UUID getPumpId();

		Long getMinutes();

	}

}
