package com.irrigaiq.farm;

import java.math.BigDecimal;
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

@Entity
@Table(name = "fields")
public class Field extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "farm_id", nullable = false, updatable = false)
	private Farm farm;

	@Column(nullable = false, length = 160)
	private String name;

	@Column(name = "area_ha", precision = 12, scale = 2)
	private BigDecimal areaHa;

	@Enumerated(EnumType.STRING)
	@Column(name = "soil_type", length = 32)
	private SoilType soilType;

	@Column(precision = 9, scale = 6)
	private BigDecimal latitude;

	@Column(precision = 9, scale = 6)
	private BigDecimal longitude;

	@Enumerated(EnumType.STRING)
	@Column(name = "irrigation_method", length = 32)
	private IrrigationMethod irrigationMethod;

	@Column(name = "application_rate_mm_h", precision = 6, scale = 2)
	private BigDecimal applicationRateMmH;

	@Column(name = "system_efficiency", precision = 3, scale = 2)
	private BigDecimal systemEfficiency;

	/** Pump that normally supplies this field (infrastructure module id). */
	@Column(name = "pump_asset_id")
	private UUID pumpAssetId;

	/** Soil covered by organic or plastic mulch, which cuts evaporation (an input to the irrigation model). */
	@Column(nullable = false)
	private boolean mulched;

	protected Field() {
	}

	public Field(Farm farm, String name, BigDecimal areaHa, SoilType soilType, BigDecimal latitude,
			BigDecimal longitude) {
		this.farm = farm;
		update(name, areaHa, soilType, latitude, longitude);
	}

	public void update(String name, BigDecimal areaHa, SoilType soilType, BigDecimal latitude, BigDecimal longitude) {
		this.name = name.strip();
		this.areaHa = Decimals.scale(areaHa, 2);
		this.soilType = soilType;
		this.latitude = Decimals.scale(latitude, 6);
		this.longitude = Decimals.scale(longitude, 6);
	}

	/**
	 * Irrigation system of the field. Rate and efficiency are optional overrides of the method's
	 * typical values, set once measured at commissioning.
	 */
	public void configureIrrigation(IrrigationMethod method, BigDecimal applicationRateMmH, BigDecimal efficiency,
			UUID pumpAssetId) {
		this.irrigationMethod = method;
		this.applicationRateMmH = Decimals.scale(applicationRateMmH, 2);
		this.systemEfficiency = Decimals.scale(efficiency, 2);
		this.pumpAssetId = pumpAssetId;
	}

	public void setMulched(boolean mulched) {
		this.mulched = mulched;
	}

	public boolean isMulched() {
		return mulched;
	}

	public IrrigationMethod getIrrigationMethod() {
		return irrigationMethod;
	}

	public BigDecimal getApplicationRateMmH() {
		return applicationRateMmH;
	}

	public BigDecimal getSystemEfficiency() {
		return systemEfficiency;
	}

	public UUID getPumpAssetId() {
		return pumpAssetId;
	}

	public Farm getFarm() {
		return farm;
	}

	public String getName() {
		return name;
	}

	public BigDecimal getAreaHa() {
		return areaHa;
	}

	public SoilType getSoilType() {
		return soilType;
	}

	public BigDecimal getLatitude() {
		return latitude;
	}

	public BigDecimal getLongitude() {
		return longitude;
	}

}
