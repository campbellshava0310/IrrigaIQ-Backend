package com.irrigaiq.telemetry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorRepository extends JpaRepository<Sensor, UUID> {

	List<Sensor> findByFarmIdOrderByNameAscDeviceIdAsc(UUID farmId);

	List<Sensor> findByFarmIdAndType(UUID farmId, SensorType type);

	Optional<Sensor> findByIdAndFarmId(UUID id, UUID farmId);

	boolean existsByFarmIdAndDeviceIdIgnoreCase(UUID farmId, String deviceId);

	@EntityGraph(attributePaths = "farm")
	Optional<Sensor> findByKeyPrefix(String keyPrefix);

}
