package com.irrigaiq.maintenance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.irrigaiq.assets.Asset;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.common.persistence.BaseEntity;
import com.irrigaiq.farm.Farm;

@Entity
@Table(name = "work_orders")
public class WorkOrder extends BaseEntity {

	/** Human-friendly sequential number (WO-1001), assigned by the database. */
	@Generated(event = EventType.INSERT)
	@Column(name = "number", nullable = false, insertable = false, updatable = false)
	private Long number;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "farm_id", nullable = false, updatable = false)
	private Farm farm;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "asset_id")
	private Asset asset;

	@Column(name = "alert_id")
	private UUID alertId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private WorkOrderKind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private WorkOrderPriority priority;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private WorkOrderStatus status;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(length = 2000)
	private String description;

	@Column(name = "due_on")
	private LocalDate dueOn;

	@Column(length = 160)
	private String assignee;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "completion_notes", length = 2000)
	private String completionNotes;

	@Column(precision = 12, scale = 2)
	private BigDecimal cost;

	@Column(name = "downtime_hours", precision = 8, scale = 2)
	private BigDecimal downtimeHours;

	protected WorkOrder() {
	}

	public WorkOrder(Farm farm, Asset asset, UUID alertId, WorkOrderDetails details, UUID createdBy) {
		this.farm = farm;
		this.asset = asset;
		this.alertId = alertId;
		this.createdBy = createdBy;
		this.status = (details.dueOn() != null) ? WorkOrderStatus.SCHEDULED : WorkOrderStatus.OPEN;
		edit(asset, details);
	}

	public void edit(Asset newAsset, WorkOrderDetails details) {
		this.asset = newAsset;
		this.kind = details.kind();
		this.priority = (details.priority() != null) ? details.priority() : WorkOrderPriority.MEDIUM;
		this.title = details.title().strip();
		this.description = clean(details.description());
		this.dueOn = details.dueOn();
		this.assignee = clean(details.assignee());
		if (status == WorkOrderStatus.OPEN && dueOn != null) {
			status = WorkOrderStatus.SCHEDULED;
		}
	}

	public void start(Instant now) {
		requireActive();
		status = WorkOrderStatus.IN_PROGRESS;
		if (startedAt == null) {
			startedAt = now;
		}
	}

	public void complete(String notes, BigDecimal cost, BigDecimal downtimeHours, Instant now) {
		requireActive();
		status = WorkOrderStatus.DONE;
		completedAt = now;
		if (startedAt == null) {
			startedAt = now;
		}
		completionNotes = clean(notes);
		this.cost = cost;
		this.downtimeHours = downtimeHours;
	}

	public void cancel(String reason) {
		requireActive();
		status = WorkOrderStatus.CANCELLED;
		completionNotes = clean(reason);
	}

	public void reopen() {
		if (status.isActive()) {
			return;
		}
		status = (dueOn != null) ? WorkOrderStatus.SCHEDULED : WorkOrderStatus.OPEN;
		completedAt = null;
	}

	private void requireActive() {
		if (!status.isActive()) {
			throw ApiException.conflict(ErrorCode.OPERATION_NOT_ALLOWED, "This work order is already closed");
		}
	}

	private static String clean(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

	public record WorkOrderDetails(WorkOrderKind kind, WorkOrderPriority priority, String title, String description,
			LocalDate dueOn, String assignee) {
	}

	public Long getNumber() {
		return number;
	}

	public Farm getFarm() {
		return farm;
	}

	public Asset getAsset() {
		return asset;
	}

	public UUID getAlertId() {
		return alertId;
	}

	public WorkOrderKind getKind() {
		return kind;
	}

	public WorkOrderPriority getPriority() {
		return priority;
	}

	public WorkOrderStatus getStatus() {
		return status;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public LocalDate getDueOn() {
		return dueOn;
	}

	public String getAssignee() {
		return assignee;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public String getCompletionNotes() {
		return completionNotes;
	}

	public BigDecimal getCost() {
		return cost;
	}

	public BigDecimal getDowntimeHours() {
		return downtimeHours;
	}

}
