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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.irrigaiq.common.persistence.BaseEntity;
import com.irrigaiq.farm.Field;

/**
 * A planned or completed irrigation of one field. Depth (mm over the field) and volume (litres)
 * are two views of the same water: 1 mm on 1 ha is 10 000 litres.
 */
@Entity
@Table(name = "irrigation_events")
public class IrrigationEvent extends BaseEntity {

	static final double LITRES_PER_MM_HA = 10_000.0;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "field_id", nullable = false, updatable = false)
	private Field field;

	@Column(name = "prediction_id")
	private UUID predictionId;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(name = "estimated_litres", precision = 12, scale = 2)
	private BigDecimal estimatedLitres;

	@Column(name = "actual_litres", precision = 12, scale = 2)
	private BigDecimal actualLitres;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private IrrigationSource source;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private IrrigationStatus status;

	@Column(name = "gross_depth_mm", precision = 7, scale = 2)
	private BigDecimal grossDepthMm;

	@Column(name = "pump_asset_id")
	private UUID pumpAssetId;

	@Column(name = "energy_kwh", precision = 10, scale = 2)
	private BigDecimal energyKwh;

	@Column(length = 500)
	private String notes;

	@Column(name = "recorded_by")
	private UUID recordedBy;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected IrrigationEvent() {
	}

	public IrrigationEvent(Field field, IrrigationStatus status, IrrigationSource source, Instant startedAt,
			int durationMinutes, double grossDepthMm, UUID pumpAssetId, Double energyKwh, String notes, UUID recordedBy,
			Instant now) {
		this.field = field;
		this.status = status;
		this.source = source;
		this.startedAt = startedAt;
		this.durationMinutes = durationMinutes;
		this.grossDepthMm = scale(grossDepthMm, 2);
		this.estimatedLitres = scale(grossDepthMm * fieldAreaHa() * LITRES_PER_MM_HA, 2);
		this.pumpAssetId = pumpAssetId;
		this.energyKwh = (energyKwh != null) ? scale(energyKwh, 2) : null;
		this.notes = clean(notes);
		this.recordedBy = recordedBy;
		if (status == IrrigationStatus.COMPLETED) {
			this.actualLitres = this.estimatedLitres;
			this.completedAt = now;
		}
	}

	/**
	 * Confirms the irrigation ran. Measured volume (flow meter) wins over the planned estimate.
	 */
	public void complete(Instant actualStart, Integer actualDurationMinutes, Double measuredLitres, Double energy,
			String completionNotes, Instant now) {
		if (actualStart != null) {
			startedAt = actualStart;
		}
		if (actualDurationMinutes != null) {
			double ratio = (double) actualDurationMinutes / durationMinutes;
			durationMinutes = actualDurationMinutes;
			if (measuredLitres == null && grossDepthMm != null) {
				grossDepthMm = scale(grossDepthMm.doubleValue() * ratio, 2); // same system rate, longer/shorter run
			}
		}
		if (measuredLitres != null) {
			actualLitres = scale(measuredLitres, 2);
			grossDepthMm = scale(measuredLitres / (fieldAreaHa() * LITRES_PER_MM_HA), 2);
		}
		else {
			actualLitres = scale(grossDepthMm.doubleValue() * fieldAreaHa() * LITRES_PER_MM_HA, 2);
		}
		if (energy != null) {
			energyKwh = scale(energy, 2);
		}
		if (completionNotes != null && !completionNotes.isBlank()) {
			notes = clean(completionNotes);
		}
		status = IrrigationStatus.COMPLETED;
		completedAt = now;
	}

	public void skip(String reason) {
		status = IrrigationStatus.SKIPPED;
		if (reason != null && !reason.isBlank()) {
			notes = clean(reason);
		}
	}

	public void estimateEnergy(Double kwh) {
		if (energyKwh == null && kwh != null) {
			energyKwh = scale(kwh, 2);
		}
	}

	/** Litres that actually went (or will go) onto the field. */
	public double effectiveLitres() {
		if (actualLitres != null) {
			return actualLitres.doubleValue();
		}
		return (estimatedLitres != null) ? estimatedLitres.doubleValue() : 0;
	}

	private double fieldAreaHa() {
		return (field.getAreaHa() != null) ? field.getAreaHa().doubleValue() : 1.0;
	}

	private static BigDecimal scale(double value, int digits) {
		return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP);
	}

	private static String clean(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

	public Field getField() {
		return field;
	}

	public UUID getPredictionId() {
		return predictionId;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public BigDecimal getEstimatedLitres() {
		return estimatedLitres;
	}

	public BigDecimal getActualLitres() {
		return actualLitres;
	}

	public IrrigationSource getSource() {
		return source;
	}

	public IrrigationStatus getStatus() {
		return status;
	}

	public BigDecimal getGrossDepthMm() {
		return grossDepthMm;
	}

	public UUID getPumpAssetId() {
		return pumpAssetId;
	}

	public BigDecimal getEnergyKwh() {
		return energyKwh;
	}

	public String getNotes() {
		return notes;
	}

	public UUID getRecordedBy() {
		return recordedBy;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

}
