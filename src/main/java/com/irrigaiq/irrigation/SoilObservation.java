package com.irrigaiq.irrigation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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
import org.hibernate.annotations.UuidGenerator;

import com.irrigaiq.farm.Field;

/**
 * A soil-moisture reading expressed as depletion of the root zone's available water
 * (0 % = field capacity). It resets the water balance on its date, which is how a farmer's
 * "feel" check, a probe or (later) a sensor corrects the model.
 */
@Entity
@Table(name = "soil_moisture_observations")
public class SoilObservation {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "field_id", nullable = false, updatable = false)
	private Field field;

	@Column(name = "observed_at", nullable = false)
	private Instant observedAt;

	@Column(name = "depletion_pct", nullable = false, precision = 5, scale = 2)
	private BigDecimal depletionPct;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ObservationMethod method;

	@Column(length = 500)
	private String notes;

	@Column(name = "recorded_by")
	private UUID recordedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected SoilObservation() {
	}

	public SoilObservation(Field field, Instant observedAt, double depletionPct, ObservationMethod method, String notes,
			UUID recordedBy, Instant now) {
		this.field = field;
		this.observedAt = observedAt;
		this.depletionPct = BigDecimal.valueOf(depletionPct).setScale(2, RoundingMode.HALF_UP);
		this.method = method;
		this.notes = (notes == null || notes.isBlank()) ? null : notes.strip();
		this.recordedBy = recordedBy;
		this.createdAt = now;
	}

	public UUID getId() {
		return id;
	}

	public Field getField() {
		return field;
	}

	public Instant getObservedAt() {
		return observedAt;
	}

	public BigDecimal getDepletionPct() {
		return depletionPct;
	}

	public ObservationMethod getMethod() {
		return method;
	}

	public String getNotes() {
		return notes;
	}

}
