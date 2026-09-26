package com.irrigaiq.assets;

import java.math.BigDecimal;
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

/**
 * A point-in-time inspection of an asset. Recorded by hand today; the same record later carries
 * sensor snapshots so the health engine sees one consistent history.
 */
@Entity
@Table(name = "asset_condition_checks")
public class AssetConditionCheck {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "asset_id", nullable = false, updatable = false)
	private Asset asset;

	@Column(name = "checked_at", nullable = false)
	private Instant checkedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "overall_condition", nullable = false, length = 16)
	private OverallCondition overallCondition;

	@Column(name = "pressure_bar", precision = 6, scale = 2)
	private BigDecimal pressureBar;

	@Column(name = "flow_m3h", precision = 10, scale = 2)
	private BigDecimal flowM3h;

	@Column(name = "power_kw", precision = 8, scale = 2)
	private BigDecimal powerKw;

	@Column(name = "hour_meter", precision = 10, scale = 1)
	private BigDecimal hourMeter;

	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	private VibrationLevel vibration;

	@Column(name = "leak_detected", nullable = false)
	private boolean leakDetected;

	@Column(name = "filter_dp_bar", precision = 5, scale = 2)
	private BigDecimal filterDpBar;

	@Column(name = "water_level_m", precision = 7, scale = 2)
	private BigDecimal waterLevelM;

	@Column(length = 1000)
	private String notes;

	@Column(name = "recorded_by")
	private UUID recordedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AssetConditionCheck() {
	}

	public AssetConditionCheck(Asset asset, CheckDetails details, UUID recordedBy, Instant now) {
		this.asset = asset;
		this.checkedAt = (details.checkedAt() != null) ? details.checkedAt() : now;
		this.overallCondition = details.overallCondition();
		this.pressureBar = details.pressureBar();
		this.flowM3h = details.flowM3h();
		this.powerKw = details.powerKw();
		this.hourMeter = details.hourMeter();
		this.vibration = details.vibration();
		this.leakDetected = Boolean.TRUE.equals(details.leakDetected());
		this.filterDpBar = details.filterDpBar();
		this.waterLevelM = details.waterLevelM();
		this.notes = (details.notes() == null || details.notes().isBlank()) ? null : details.notes().strip();
		this.recordedBy = recordedBy;
		this.createdAt = now;
	}

	/** Specific energy (kWh per m³ pumped), the key pump-efficiency indicator. */
	public Double energyKwhPerM3() {
		if (powerKw == null || flowM3h == null || flowM3h.signum() <= 0) {
			return null;
		}
		return Math.round(powerKw.doubleValue() / flowM3h.doubleValue() * 1000) / 1000.0;
	}

	public record CheckDetails(Instant checkedAt, OverallCondition overallCondition, BigDecimal pressureBar,
			BigDecimal flowM3h, BigDecimal powerKw, BigDecimal hourMeter, VibrationLevel vibration, Boolean leakDetected,
			BigDecimal filterDpBar, BigDecimal waterLevelM, String notes) {
	}

	public UUID getId() {
		return id;
	}

	public Asset getAsset() {
		return asset;
	}

	public Instant getCheckedAt() {
		return checkedAt;
	}

	public OverallCondition getOverallCondition() {
		return overallCondition;
	}

	public BigDecimal getPressureBar() {
		return pressureBar;
	}

	public BigDecimal getFlowM3h() {
		return flowM3h;
	}

	public BigDecimal getPowerKw() {
		return powerKw;
	}

	public BigDecimal getHourMeter() {
		return hourMeter;
	}

	public VibrationLevel getVibration() {
		return vibration;
	}

	public boolean isLeakDetected() {
		return leakDetected;
	}

	public BigDecimal getFilterDpBar() {
		return filterDpBar;
	}

	public BigDecimal getWaterLevelM() {
		return waterLevelM;
	}

	public String getNotes() {
		return notes;
	}

	public UUID getRecordedBy() {
		return recordedBy;
	}

}
