package com.irrigaiq.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import com.irrigaiq.security.Role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	/** Row lock used to serialise concurrent failed-login bookkeeping for one account. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(UUID id);

	@Query("select count(u) from User u where :role member of u.roles and u.enabled = true")
	long countEnabledWithRole(Role role);

	@Query("select count(u) from User u where :role member of u.roles")
	long countWithRole(Role role);

	long countByEnabledTrue();

	@Query("select count(u) from User u where u.lockedUntil > :now")
	long countLockedAt(Instant now);

}
