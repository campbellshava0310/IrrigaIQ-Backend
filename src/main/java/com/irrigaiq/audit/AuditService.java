package com.irrigaiq.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;

import com.irrigaiq.common.web.RequestIdFilter;
import com.irrigaiq.common.web.RequestMetadata;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit events. Joins the caller's transaction when there is one, so a business change and
 * its audit record commit (or roll back) together; failed logins run outside a transaction and
 * are therefore persisted on their own.
 */
@Service
public class AuditService {

	private final AuditEventRepository repository;

	private final Clock clock;

	public AuditService(AuditEventRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public void record(AuditEntry entry) {
		UUID actorId = entry.actorUserId;
		String actorEmail = entry.actorEmail;
		if (actorId == null && actorEmail == null) {
			AuthenticatedUser current = currentUser();
			if (current != null) {
				actorId = current.id();
				actorEmail = current.email();
			}
		}
		RequestMetadata request = RequestMetadata.current();
		Map<String, Object> details = entry.details.isEmpty() ? null : Map.copyOf(entry.details);
		repository.save(new AuditEvent(clock.instant(), entry.action, entry.outcome, actorId, actorEmail,
				entry.targetType, entry.targetId, request.ipAddress(), request.userAgent(),
				RequestIdFilter.currentRequestId(), details));
	}

	@Transactional(readOnly = true)
	public Page<AuditEvent> search(AuditSearch search, Pageable pageable) {
		Specification<AuditEvent> specification = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (search.action() != null) {
				predicates.add(cb.equal(root.get("action"), search.action()));
			}
			if (search.outcome() != null) {
				predicates.add(cb.equal(root.get("outcome"), search.outcome()));
			}
			if (search.actorUserId() != null) {
				predicates.add(cb.equal(root.get("actorUserId"), search.actorUserId()));
			}
			if (search.targetId() != null) {
				predicates.add(cb.equal(root.get("targetId"), search.targetId()));
			}
			if (search.from() != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), search.from()));
			}
			if (search.to() != null) {
				predicates.add(cb.lessThan(root.get("occurredAt"), search.to()));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		return repository.findAll(specification, pageable);
	}

	private static AuthenticatedUser currentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
			return user;
		}
		return null;
	}

	public record AuditSearch(AuditAction action, AuditOutcome outcome, UUID actorUserId, String targetId,
			Instant from, Instant to) {
	}

}
