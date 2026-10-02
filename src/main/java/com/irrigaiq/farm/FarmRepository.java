package com.irrigaiq.farm;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FarmRepository extends JpaRepository<Farm, UUID>, JpaSpecificationExecutor<Farm> {

	@EntityGraph(attributePaths = "owner")
	Optional<Farm> findWithOwnerById(UUID id);

	@Override
	@EntityGraph(attributePaths = "owner")
	Page<Farm> findAll(Specification<Farm> specification, Pageable pageable);

}
