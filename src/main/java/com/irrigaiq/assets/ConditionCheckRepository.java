package com.irrigaiq.assets;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConditionCheckRepository extends JpaRepository<AssetConditionCheck, UUID> {

	List<AssetConditionCheck> findByAssetIdOrderByCheckedAtDesc(UUID assetId, Pageable page);

	@Query("select c from AssetConditionCheck c where c.asset.farm.id = :farmId and c.checkedAt >= :since")
	List<AssetConditionCheck> findByFarmSince(UUID farmId, Instant since);

}
