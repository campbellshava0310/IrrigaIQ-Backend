package com.irrigaiq.farm;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.irrigaiq.common.persistence.BaseEntity;
import com.irrigaiq.identity.User;

@Entity
@Table(name = "farms")
public class Farm extends BaseEntity {

	public static final String DEFAULT_TIMEZONE = "Africa/Harare";

	public static final String DEFAULT_CURRENCY = "USD";

	public static final int DEFAULT_BASELINE_INTERVAL_DAYS = 3;

	public static final BigDecimal DEFAULT_BASELINE_DEPTH_MM = new BigDecimal("25.00");

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false, updatable = false)
	private User owner;

	@Column(nullable = false, length = 160)
	private String name;

	@Column(length = 255)
	private String location;

	@Column(name = "area_ha", precision = 12, scale = 2)
	private BigDecimal areaHa;

	@Column(precision = 9, scale = 6)
	private BigDecimal latitude;

	@Column(precision = 9, scale = 6)
	private BigDecimal longitude;

	@Column(nullable = false, length = 64)
	private String timezone = DEFAULT_TIMEZONE;

	@Column(nullable = false, length = 3)
	private String currency = DEFAULT_CURRENCY;

	@Column(name = "electricity_tariff", precision = 10, scale = 4)
	private BigDecimal electricityTariff;

	@Column(name = "baseline_interval_days", nullable = false)
	private int baselineIntervalDays = DEFAULT_BASELINE_INTERVAL_DAYS;

	@Column(name = "baseline_depth_mm", nullable = false, precision = 6, scale = 2)
	private BigDecimal baselineDepthMm = DEFAULT_BASELINE_DEPTH_MM;

	protected Farm() {
	}

	public Farm(User owner, FarmDetails details) {
		this.owner = owner;
		update(details);
	}

	public void update(FarmDetails details) {
		this.name = details.name().strip();
		this.location = (details.location() != null) ? details.location().strip() : null;
		this.areaHa = Decimals.scale(details.areaHa(), 2);
		this.latitude = Decimals.scale(details.latitude(), 6);
		this.longitude = Decimals.scale(details.longitude(), 6);
		this.timezone = (details.timezone() != null) ? ZoneId.of(details.timezone()).getId() : DEFAULT_TIMEZONE;
		this.currency = (details.currency() != null) ? details.currency().toUpperCase() : DEFAULT_CURRENCY;
		this.electricityTariff = Decimals.scale(details.electricityTariff(), 4);
		this.baselineIntervalDays = (details.baselineIntervalDays() != null) ? details.baselineIntervalDays()
				: DEFAULT_BASELINE_INTERVAL_DAYS;
		this.baselineDepthMm = (details.baselineDepthMm() != null) ? Decimals.scale(details.baselineDepthMm(), 2)
				: DEFAULT_BASELINE_DEPTH_MM;
	}

	public boolean isOwnedBy(UUID userId) {
		return owner.getId().equals(userId);
	}

	public ZoneId zone() {
		return ZoneId.of(timezone);
	}

	public User getOwner() {
		return owner;
	}

	public String getName() {
		return name;
	}

	public String getLocation() {
		return location;
	}

	public BigDecimal getAreaHa() {
		return areaHa;
	}

	public BigDecimal getLatitude() {
		return latitude;
	}

	public BigDecimal getLongitude() {
		return longitude;
	}

	public String getTimezone() {
		return timezone;
	}

	public String getCurrency() {
		return currency;
	}

	public BigDecimal getElectricityTariff() {
		return electricityTariff;
	}

	public int getBaselineIntervalDays() {
		return baselineIntervalDays;
	}

	public BigDecimal getBaselineDepthMm() {
		return baselineDepthMm;
	}

}
