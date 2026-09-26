package com.irrigaiq.assets;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
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

/** A piece of water infrastructure: source, pump, filter, pipework, application system or control. */
@Entity
@Table(name = "assets")
public class Asset extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "farm_id", nullable = false, updatable = false)
	private Farm farm;

	@Column(name = "field_id")
	private UUID fieldId;

	@Column(name = "parent_id")
	private UUID parentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "asset_type", nullable = false, length = 32)
	private AssetType type;

	@Column(nullable = false, length = 160)
	private String name;

	@Column(length = 120)
	private String manufacturer;

	@Column(length = 120)
	private String model;

	@Column(name = "serial_number", length = 120)
	private String serialNumber;

	@Column(name = "installed_on")
	private LocalDate installedOn;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Criticality criticality;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private AssetStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "power_source", length = 16)
	private PowerSource powerSource;

	@Column(name = "rated_power_kw", precision = 8, scale = 2)
	private BigDecimal ratedPowerKw;

	@Column(name = "rated_flow_m3h", precision = 10, scale = 2)
	private BigDecimal ratedFlowM3h;

	@Column(name = "rated_head_m", precision = 8, scale = 2)
	private BigDecimal ratedHeadM;

	@Column(name = "capacity_m3", precision = 14, scale = 2)
	private BigDecimal capacityM3;

	@Column(name = "annual_allocation_m3", precision = 14, scale = 2)
	private BigDecimal annualAllocationM3;

	@Column(name = "operating_window_start")
	private LocalTime operatingWindowStart;

	@Column(name = "operating_window_end")
	private LocalTime operatingWindowEnd;

	@Column(name = "service_interval_days")
	private Integer serviceIntervalDays;

	@Column(name = "service_interval_hours")
	private Integer serviceIntervalHours;

	@Column(name = "expected_life_years")
	private Integer expectedLifeYears;

	@Column(name = "operating_hours", nullable = false, precision = 10, scale = 1)
	private BigDecimal operatingHours = BigDecimal.ZERO;

	@Column(name = "hours_at_last_service", nullable = false, precision = 10, scale = 1)
	private BigDecimal hoursAtLastService = BigDecimal.ZERO;

	@Column(name = "last_service_on")
	private LocalDate lastServiceOn;

	@Column(name = "baseline_energy_kwh_per_m3", precision = 8, scale = 4)
	private BigDecimal baselineEnergyKwhPerM3;

	@Column(length = 1000)
	private String notes;

	protected Asset() {
	}

	public Asset(Farm farm, AssetDetails details) {
		this.farm = farm;
		update(details);
	}

	public void update(AssetDetails details) {
		this.type = details.type();
		this.name = details.name().strip();
		this.fieldId = details.fieldId();
		this.parentId = details.parentId();
		this.manufacturer = trim(details.manufacturer());
		this.model = trim(details.model());
		this.serialNumber = trim(details.serialNumber());
		this.installedOn = details.installedOn();
		this.criticality = (details.criticality() != null) ? details.criticality() : Criticality.MEDIUM;
		this.status = (details.status() != null) ? details.status() : AssetStatus.OPERATIONAL;
		this.powerSource = details.powerSource();
		this.ratedPowerKw = details.ratedPowerKw();
		this.ratedFlowM3h = details.ratedFlowM3h();
		this.ratedHeadM = details.ratedHeadM();
		this.capacityM3 = details.capacityM3();
		this.annualAllocationM3 = details.annualAllocationM3();
		this.operatingWindowStart = details.operatingWindowStart();
		this.operatingWindowEnd = details.operatingWindowEnd();
		this.serviceIntervalDays = details.serviceIntervalDays();
		this.serviceIntervalHours = details.serviceIntervalHours();
		this.expectedLifeYears = details.expectedLifeYears();
		this.lastServiceOn = details.lastServiceOn();
		this.baselineEnergyKwhPerM3 = details.baselineEnergyKwhPerM3();
		this.notes = trim(details.notes());
		if (details.operatingHours() != null) {
			this.operatingHours = details.operatingHours().setScale(1, RoundingMode.HALF_UP);
		}
	}

	/** Accumulates pump run time from completed irrigation. */
	public void addRunHours(double hours) {
		if (hours > 0) {
			operatingHours = operatingHours.add(BigDecimal.valueOf(hours)).setScale(1, RoundingMode.HALF_UP);
		}
	}

	/** Corrects the counter when a logged irrigation is deleted. */
	public void removeRunHours(double hours) {
		if (hours > 0) {
			operatingHours = operatingHours.subtract(BigDecimal.valueOf(hours))
				.max(BigDecimal.ZERO)
				.setScale(1, RoundingMode.HALF_UP);
		}
	}

	/** A meter reading never moves the counter backwards. */
	public void observeHourMeter(BigDecimal reading) {
		if (reading != null && reading.compareTo(operatingHours) > 0) {
			operatingHours = reading.setScale(1, RoundingMode.HALF_UP);
		}
	}

	/** A completed preventive service restarts both the calendar and run-hour intervals. */
	public void recordService(LocalDate on) {
		lastServiceOn = on;
		hoursAtLastService = operatingHours;
	}

	public void changeStatus(AssetStatus newStatus) {
		this.status = newStatus;
	}

	private static String trim(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

	public Farm getFarm() {
		return farm;
	}

	public UUID getFieldId() {
		return fieldId;
	}

	public UUID getParentId() {
		return parentId;
	}

	public AssetType getType() {
		return type;
	}

	public String getName() {
		return name;
	}

	public String getManufacturer() {
		return manufacturer;
	}

	public String getModel() {
		return model;
	}

	public String getSerialNumber() {
		return serialNumber;
	}

	public LocalDate getInstalledOn() {
		return installedOn;
	}

	public Criticality getCriticality() {
		return criticality;
	}

	public AssetStatus getStatus() {
		return status;
	}

	public PowerSource getPowerSource() {
		return powerSource;
	}

	public BigDecimal getRatedPowerKw() {
		return ratedPowerKw;
	}

	public BigDecimal getRatedFlowM3h() {
		return ratedFlowM3h;
	}

	public BigDecimal getRatedHeadM() {
		return ratedHeadM;
	}

	public BigDecimal getCapacityM3() {
		return capacityM3;
	}

	public BigDecimal getAnnualAllocationM3() {
		return annualAllocationM3;
	}

	public LocalTime getOperatingWindowStart() {
		return operatingWindowStart;
	}

	public LocalTime getOperatingWindowEnd() {
		return operatingWindowEnd;
	}

	public Integer getServiceIntervalDays() {
		return serviceIntervalDays;
	}

	public Integer getServiceIntervalHours() {
		return serviceIntervalHours;
	}

	public Integer getExpectedLifeYears() {
		return expectedLifeYears;
	}

	public BigDecimal getOperatingHours() {
		return operatingHours;
	}

	public BigDecimal getHoursAtLastService() {
		return hoursAtLastService;
	}

	public LocalDate getLastServiceOn() {
		return lastServiceOn;
	}

	public BigDecimal getBaselineEnergyKwhPerM3() {
		return baselineEnergyKwhPerM3;
	}

	public String getNotes() {
		return notes;
	}

}
