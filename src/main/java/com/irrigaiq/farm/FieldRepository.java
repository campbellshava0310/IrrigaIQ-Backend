package com.irrigaiq.farm;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FieldRepository extends JpaRepository<Field, UUID> {

	List<Field> findByFarmIdOrderByNameAsc(UUID farmId);

	Optional<Field> findByIdAndFarmId(UUID id, UUID farmId);

	boolean existsByFarmIdAndNameIgnoreCase(UUID farmId, String name);

	boolean existsByFarmIdAndNameIgnoreCaseAndIdNot(UUID farmId, String name, UUID id);

	@Query("select f.farm.id as farmId, count(f) as total from Field f where f.farm.id in :farmIds group by f.farm.id")
	List<FarmFieldCount> countByFarmIds(Collection<UUID> farmIds);

	/** Centre of the farm's georeferenced fields; locates weather when the farm has no coordinates. */
	@Query("""
			select avg(f.latitude) as latitude, avg(f.longitude) as longitude from Field f
			where f.farm.id = :farmId and f.latitude is not null and f.longitude is not null
			""")
	Coordinates averageCoordinates(UUID farmId);

	interface Coordinates {

		Double getLatitude();

		Double getLongitude();

	}

	interface FarmFieldCount {

		UUID getFarmId();

		long getTotal();

	}

}
