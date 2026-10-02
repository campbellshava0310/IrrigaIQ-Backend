package com.irrigaiq.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	/**
	 * Locks the token row so two concurrent refreshes with the same token cannot both rotate it.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :tokenHash")
	Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash);

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/** Active sessions: each has exactly one live token at the head of its rotation chain. */
	@Query("""
			select t from RefreshToken t
			where t.user.id = :userId and t.revokedAt is null and t.expiresAt > :now
			order by t.lastUsedAt desc
			""")
	List<RefreshToken> findActiveByUserId(UUID userId, Instant now);

	@Query("select count(t) from RefreshToken t where t.revokedAt is null and t.expiresAt > :now")
	long countActive(Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason, t.updatedAt = :now
			where t.sessionId = :sessionId and t.revokedAt is null
			""")
	int revokeSession(UUID sessionId, RevocationReason reason, Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason, t.updatedAt = :now
			where t.user.id = :userId and t.sessionId = :sessionId and t.revokedAt is null
			""")
	int revokeUserSession(UUID userId, UUID sessionId, RevocationReason reason, Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason, t.updatedAt = :now
			where t.user.id = :userId and t.revokedAt is null
			""")
	int revokeAllForUser(UUID userId, RevocationReason reason, Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason, t.updatedAt = :now
			where t.user.id = :userId and t.sessionId <> :keepSessionId and t.revokedAt is null
			""")
	int revokeAllForUserExcept(UUID userId, UUID keepSessionId, RevocationReason reason, Instant now);

	/**
	 * Rotated tokens are kept until they expire so that replaying one is still detected as reuse.
	 */
	@Modifying
	@Query("delete from RefreshToken t where t.expiresAt < :cutoff")
	int deleteExpiredBefore(Instant cutoff);

}
