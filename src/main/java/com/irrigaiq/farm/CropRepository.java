package com.irrigaiq.farm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CropRepository extends JpaRepository<Crop, UUID> {

	List<Crop> findByFieldIdOrderByPlantingDateDesc(UUID fieldId);

	Optional<Crop> findByIdAndFieldId(UUID id, UUID fieldId);

	/** Every crop on the farm, most recently planted first (unplanted last). */
	@Query("select c from Crop c join fetch c.field f where f.farm.id = :farmId order by c.plantingDate desc nulls last")
	List<Crop> findByFarm(UUID farmId);

}
