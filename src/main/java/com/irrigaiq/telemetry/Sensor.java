package com.irrigaiq.telemetry;

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
import com.irrigaiq.farm.Farm;

/**
 * A connected device (ESP32 probe, pump monitor, water meter). It authenticates with its own key,
 * of which only a SHA-256 hash and a short lookup prefix are stored.
 */
@Entity
@Table(name = "sensors")
public class Sensor extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "farm_id", nullable = false, updatable = false)
	private Farm farm;

	@Column(name = "field_id")
	private UUID fieldId;

	@Column(name = "asset_id")
	private UUID assetId;

	@Enumerated(EnumType.STRING)
	@Column(name = "sensor_type", nullable = false, length = 32, updatable = false)
	private SensorType type;

	/** The hardware's own identifier (e.g. its MAC address or a label), unique within the farm. */
	@Column(name = "device_id", nullable = false, length = 128)
	private String deviceId;

	@Column(length = 160)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private DeviceStatus status = DeviceStatus.ACTIVE;

	@Column(name = "key_prefix", length = 16)
	private String keyPrefix;

	@Column(name = "key_hash", length = 64)
	private String keyHash;

	@Column(name = "key_rotated_at")
	private Instant keyRotatedAt;

	@Column(name = "last_seen_at")
	private Instant lastSeenAt;

	@Column(name = "field_capacity_pct", precision = 5, scale = 2)
	private BigDecimal fieldCapacityPct;

	@Column(name = "wilting_point_pct", precision = 5, scale = 2)
	private BigDecimal wiltingPointPct;

	protected Sensor() {
	}

	public Sensor(Farm farm, SensorType type, String deviceId, String name) {
		this.farm = farm;
		this.type = type;
		this.deviceId = deviceId.strip();
		rename(name);
	}

	public void rename(String name) {
		this.name = (name == null || name.isBlank()) ? null : name.strip();
	}

	public void attach(UUID fieldId, UUID assetId) {
		this.fieldId = fieldId;
		this.assetId = assetId;
	}

	public void calibrate(BigDecimal fieldCapacityPct, BigDecimal wiltingPointPct) {
		this.fieldCapacityPct = (fieldCapacityPct != null) ? fieldCapacityPct.setScale(2, RoundingMode.HALF_UP) : null;
		this.wiltingPointPct = (wiltingPointPct != null) ? wiltingPointPct.setScale(2, RoundingMode.HALF_UP) : null;
	}

	public void changeStatus(DeviceStatus status) {
		this.status = status;
	}

	void issueKey(DeviceKeys.Issued key, Instant now) {
		this.keyPrefix = key.prefix();
		this.keyHash = key.hash();
		this.keyRotatedAt = now;
	}

	void seen(Instant at) {
		if (lastSeenAt == null || at.isAfter(lastSeenAt)) {
			this.lastSeenAt = at;
		}
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

	public SensorType getType() {
		return type;
	}

	public String getDeviceId() {
		return deviceId;
	}

	public String getName() {
		return name;
	}

	/** The name to show: the given name, or the hardware id. */
	public String displayName() {
		return (name != null) ? name : deviceId;
	}

	public DeviceStatus getStatus() {
		return status;
	}

	public String getKeyPrefix() {
		return keyPrefix;
	}

	String getKeyHash() {
		return keyHash;
	}

	public Instant getKeyRotatedAt() {
		return keyRotatedAt;
	}

	public Instant getLastSeenAt() {
		return lastSeenAt;
	}

	public BigDecimal getFieldCapacityPct() {
		return fieldCapacityPct;
	}

	public BigDecimal getWiltingPointPct() {
		return wiltingPointPct;
	}

}
