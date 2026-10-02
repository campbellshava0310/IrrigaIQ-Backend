package com.irrigaiq.assets;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

	List<Asset> findByFarmIdOrderByNameAsc(UUID farmId);

	Optional<Asset> findByIdAndFarmId(UUID id, UUID farmId);

	long countByFarmId(UUID farmId);

}
