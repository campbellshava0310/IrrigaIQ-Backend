package com.irrigaiq.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fluent description of an audit event. Request metadata (IP, user agent, request id) and, when
 * not set explicitly, the actor are filled in by {@link AuditService}.
 */
public final class AuditEntry {

	final AuditAction action;

	AuditOutcome outcome = AuditOutcome.SUCCESS;

	UUID actorUserId;

	String actorEmail;

	String targetType;

	String targetId;

	final Map<String, Object> details = new LinkedHashMap<>();

	private AuditEntry(AuditAction action) {
		this.action = action;
	}

	public static AuditEntry of(AuditAction action) {
		return new AuditEntry(action);
	}

	public AuditEntry actor(UUID userId, String email) {
		this.actorUserId = userId;
		this.actorEmail = email;
		return this;
	}

	public AuditEntry target(String type, Object id) {
		this.targetType = type;
		this.targetId = (id != null) ? id.toString() : null;
		return this;
	}

	public AuditEntry failed() {
		this.outcome = AuditOutcome.FAILURE;
		return this;
	}

	public AuditEntry detail(String key, Object value) {
		if (value != null) {
			this.details.put(key, value);
		}
		return this;
	}

}
