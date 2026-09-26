package com.irrigaiq.farm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.irrigaiq.identity.User;

/**
 * Read models returned by the farm services. Built inside the service transaction so lazy
 * associations never leak into the web layer; also the API response bodies.
 */
public final class FarmViews {

	private FarmViews() {
	}

	public record OwnerView(UUID id, String fullName, String email) {

		static OwnerView from(User user) {
			return new OwnerView(user.getId(), user.getFullName(), user.getEmail());
		}

	}

	public record FarmView(UUID id, String name, String location, BigDecimal areaHa, BigDecimal latitude,
			BigDecimal longitude, String timezone, String currency, BigDecimal electricityTariff,
			int baselineIntervalDays, BigDecimal baselineDepthMm, OwnerView owner, long fieldCount, Instant createdAt,
			Instant updatedAt) {

		static FarmView from(Farm farm, long fieldCount) {
			return new FarmView(farm.getId(), farm.getName(), farm.getLocation(), farm.getAreaHa(), farm.getLatitude(),
					farm.getLongitude(), farm.getTimezone(), farm.getCurrency(), farm.getElectricityTariff(),
					farm.getBaselineIntervalDays(), farm.getBaselineDepthMm(), OwnerView.from(farm.getOwner()),
					fieldCount, farm.getCreatedAt(), farm.getUpdatedAt());
		}

	}

	public record FieldView(UUID id, UUID farmId, String name, BigDecimal areaHa, SoilType soilType,
			BigDecimal latitude, BigDecimal longitude, IrrigationMethod irrigationMethod, BigDecimal applicationRateMmH,
			BigDecimal systemEfficiency, UUID pumpAssetId, Instant createdAt, Instant updatedAt) {

		public static FieldView from(Field field) {
			return new FieldView(field.getId(), field.getFarm().getId(), field.getName(), field.getAreaHa(),
					field.getSoilType(), field.getLatitude(), field.getLongitude(), field.getIrrigationMethod(),
					field.getApplicationRateMmH(), field.getSystemEfficiency(), field.getPumpAssetId(),
					field.getCreatedAt(), field.getUpdatedAt());
		}

	}

	public record CropView(UUID id, UUID fieldId, CropType cropType, String variety, LocalDate plantingDate,
			GrowthStage growthStage, BigDecimal expectedYieldTHa, Instant createdAt, Instant updatedAt) {

		static CropView from(Crop crop) {
			return new CropView(crop.getId(), crop.getField().getId(), crop.getCropType(), crop.getVariety(),
					crop.getPlantingDate(), crop.getGrowthStage(), crop.getExpectedYieldTHa(), crop.getCreatedAt(),
					crop.getUpdatedAt());
		}

	}

}
