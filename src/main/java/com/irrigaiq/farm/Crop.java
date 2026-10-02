package com.irrigaiq.farm;

import java.math.BigDecimal;
import java.time.LocalDate;

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
@Table(name = "crops")
public class Crop extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "field_id", nullable = false, updatable = false)
	private Field field;

	@Enumerated(EnumType.STRING)
	@Column(name = "crop_type", nullable = false, length = 32)
	private CropType cropType;

	@Column(length = 120)
	private String variety;

	@Column(name = "planting_date")
	private LocalDate plantingDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "growth_stage", nullable = false, length = 32)
	private GrowthStage growthStage;

	@Column(name = "expected_yield_t_ha", precision = 7, scale = 2)
	private BigDecimal expectedYieldTHa;

	protected Crop() {
	}

	public Crop(Field field, CropType cropType, String variety, LocalDate plantingDate, GrowthStage growthStage,
			BigDecimal expectedYieldTHa) {
		this.field = field;
		update(cropType, variety, plantingDate, growthStage, expectedYieldTHa);
	}

	public void update(CropType cropType, String variety, LocalDate plantingDate, GrowthStage growthStage,
			BigDecimal expectedYieldTHa) {
		this.cropType = cropType;
		this.variety = (variety != null) ? variety.strip() : null;
		this.plantingDate = plantingDate;
		this.growthStage = growthStage;
		this.expectedYieldTHa = Decimals.scale(expectedYieldTHa, 2);
	}

	public Field getField() {
		return field;
	}

	public CropType getCropType() {
		return cropType;
	}

	public String getVariety() {
		return variety;
	}

	public LocalDate getPlantingDate() {
		return plantingDate;
	}

	public GrowthStage getGrowthStage() {
		return growthStage;
	}

	public BigDecimal getExpectedYieldTHa() {
		return expectedYieldTHa;
	}

}
