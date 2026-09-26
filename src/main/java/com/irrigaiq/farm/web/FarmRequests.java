package com.irrigaiq.farm.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.irrigaiq.common.web.ValidTimezone;
import com.irrigaiq.farm.CropService.CropDetails;
import com.irrigaiq.farm.CropType;
import com.irrigaiq.farm.FarmDetails;
import com.irrigaiq.farm.FieldService.FieldDetails;
import com.irrigaiq.farm.GrowthStage;
import com.irrigaiq.farm.SoilType;

/** Request bodies for farm, field and crop endpoints. Creates and full updates share a shape. */
public final class FarmRequests {

	private FarmRequests() {
	}

	public record FarmRequest(@NotBlank @Size(max = 160) String name, @Size(max = 255) String location,
			@Positive @Digits(integer = 10, fraction = 2) BigDecimal areaHa,
			@DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal latitude,
			@DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude,
			@ValidTimezone String timezone, @Pattern(regexp = "[A-Za-z]{3}") String currency,
			@PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal electricityTariff,
			@Min(1) @Max(30) Integer baselineIntervalDays,
			@Positive @DecimalMax("200") @Digits(integer = 4, fraction = 2) BigDecimal baselineDepthMm) {

		FarmDetails toDetails() {
			return new FarmDetails(name, location, areaHa, latitude, longitude, timezone, currency, electricityTariff,
					baselineIntervalDays, baselineDepthMm);
		}

	}

	public record FieldRequest(@NotBlank @Size(max = 160) String name,
			@Positive @Digits(integer = 10, fraction = 2) BigDecimal areaHa, SoilType soilType,
			@DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal latitude,
			@DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude) {

		FieldDetails toDetails() {
			return new FieldDetails(name, areaHa, soilType, latitude, longitude);
		}

	}

	public record CropRequest(@NotNull CropType cropType, @Size(max = 120) String variety, LocalDate plantingDate,
			@NotNull GrowthStage growthStage,
			@Positive @Digits(integer = 5, fraction = 2) BigDecimal expectedYieldTHa) {

		CropDetails toDetails() {
			return new CropDetails(cropType, variety, plantingDate, growthStage, expectedYieldTHa);
		}

	}

}
