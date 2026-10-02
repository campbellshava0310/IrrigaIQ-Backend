package com.irrigaiq.alerts;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import com.irrigaiq.farm.Farm;

/**
 * Something that needs a person's attention. Rule-raised alerts carry a dedupe key so repeated
 * evaluations update one alert instead of creating a stream of duplicates.
 */
@Entity
@Table(name = "alerts")
public class Alert {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "farm_id", nullable = false, updatable = false)
	private Farm farm;

	@Column(name = "field_id")
	private UUID fieldId;

	@Column(name = "sensor_id")
	private UUID sensorId;

	@Column(name = "asset_id")
	private UUID assetId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 48)
	private AlertType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private AlertSeverity severity;

	@Column(length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String message;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, Object> details;

	@Column(name = "dedupe_key", length = 160)
	private String dedupeKey;

	@Column(nullable = false, length = 16)
	private String source;

	@Column(name = "work_order_id")
	private UUID workOrderId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	@Column(name = "acknowledged_at")
	private Instant acknowledgedAt;

	@Column(name = "acknowledged_by")
	private UUID acknowledgedBy;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	@Column(name = "resolved_by")
	private UUID resolvedBy;

	protected Alert() {
	}

	Alert(Farm farm, String dedupeKey, String source, Instant now) {
		this.farm = farm;
		this.dedupeKey = dedupeKey;
		this.source = source;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** Applies the latest evaluation; an escalation needs a fresh acknowledgement. */
	void apply(AlertType newType, AlertSeverity newSeverity, String newTitle, String newMessage, UUID newFieldId,
			UUID newAssetId, Map<String, Object> newDetails, Instant now) {
		if (severity != null && newSeverity.ordinal() > severity.ordinal()) {
			acknowledgedAt = null;
			acknowledgedBy = null;
		}
		type = newType;
		severity = newSeverity;
		title = newTitle;
		message = newMessage;
		fieldId = newFieldId;
		assetId = newAssetId;
		details = newDetails;
		updatedAt = now;
	}

	void acknowledge(UUID userId, Instant now) {
		if (acknowledgedAt == null) {
			acknowledgedAt = now;
			acknowledgedBy = userId;
			updatedAt = now;
		}
	}

	/** @param userId null when the condition cleared by itself */
	void resolve(UUID userId, Instant now) {
		if (resolvedAt == null) {
			resolvedAt = now;
			resolvedBy = userId;
			updatedAt = now;
			if (userId == null) {
				Map<String, Object> merged = (details != null) ? new HashMap<>(details) : new HashMap<>();
				merged.put("autoResolved", true);
				details = merged;
			}
		}
	}

	void linkWorkOrder(UUID workOrderId, Instant now) {
		this.workOrderId = workOrderId;
		this.updatedAt = now;
	}

	public AlertState state() {
		if (resolvedAt != null) {
			return AlertState.RESOLVED;
		}
		return (acknowledgedAt != null) ? AlertState.ACKNOWLEDGED : AlertState.OPEN;
	}

	public UUID getId() {
		return id;
	}

	public Farm getFarm() {
		return farm;
	}

	public UUID getFieldId() {
		return fieldId;
	}

	public UUID getAssetId() {
		return assetId;
	}

	public AlertType getType() {
		return type;
	}

	public AlertSeverity getSeverity() {
		return severity;
	}

	public String getTitle() {
		return title;
	}

	public String getMessage() {
		return message;
	}

	public Map<String, Object> getDetails() {
		return details;
	}

	public String getDedupeKey() {
		return dedupeKey;
	}

	public String getSource() {
		return source;
	}

	public UUID getWorkOrderId() {
		return workOrderId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getAcknowledgedAt() {
		return acknowledgedAt;
	}

	public Instant getResolvedAt() {
		return resolvedAt;
	}

	public UUID getResolvedBy() {
		return resolvedBy;
	}

}
