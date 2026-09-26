package com.irrigaiq.maintenance;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID> {

	@EntityGraph(attributePaths = "asset")
	List<WorkOrder> findByFarmIdOrderByCreatedAtDesc(UUID farmId);

	@EntityGraph(attributePaths = "asset")
	Optional<WorkOrder> findByIdAndFarmId(UUID id, UUID farmId);

	@Query("""
			select w.asset.id as assetId, count(w) as total from WorkOrder w
			where w.farm.id = :farmId and w.kind = com.irrigaiq.maintenance.WorkOrderKind.CORRECTIVE
			  and w.status in :statuses and w.asset is not null
			group by w.asset.id
			""")
	List<AssetCount> countByAsset(UUID farmId, Collection<WorkOrderStatus> statuses);

	interface AssetCount {

		UUID getAssetId();

		long getTotal();

	}

}
