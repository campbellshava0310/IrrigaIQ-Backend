package com.irrigaiq.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** Immutable, append-only audit record. */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 64)
	private AuditAction action;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private AuditOutcome outcome;

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	@Column(name = "actor_email", length = 320)
	private String actorEmail;

	@Column(name = "target_type", length = 64)
	private String targetType;

	@Column(name = "target_id", length = 64)
	private String targetId;

	@Column(name = "ip_address", length = 64)
	private String ipAddress;

	@Column(name = "user_agent", length = 512)
	private String userAgent;

	@Column(name = "request_id", length = 64)
	private String requestId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, Object> details;

	protected AuditEvent() {
	}

	AuditEvent(Instant occurredAt, AuditAction action, AuditOutcome outcome, UUID actorUserId, String actorEmail,
			String targetType, String targetId, String ipAddress, String userAgent, String requestId,
			Map<String, Object> details) {
		this.occurredAt = occurredAt;
		this.action = action;
		this.outcome = outcome;
		this.actorUserId = actorUserId;
		this.actorEmail = actorEmail;
		this.targetType = targetType;
		this.targetId = targetId;
		this.ipAddress = ipAddress;
		this.userAgent = userAgent;
		this.requestId = requestId;
		this.details = details;
	}

	public UUID getId() {
		return id;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public AuditAction getAction() {
		return action;
	}

	public AuditOutcome getOutcome() {
		return outcome;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public String getActorEmail() {
		return actorEmail;
	}

	public String getTargetType() {
		return targetType;
	}

	public String getTargetId() {
		return targetId;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public String getRequestId() {
		return requestId;
	}

	public Map<String, Object> getDetails() {
		return details;
	}

}
