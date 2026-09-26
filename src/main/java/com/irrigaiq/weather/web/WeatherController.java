package com.irrigaiq.weather.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.weather.WeatherDay;
import com.irrigaiq.weather.WeatherService;
import com.irrigaiq.weather.WeatherService.WeatherSeries;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/farms/{farmId}/weather")
@Tag(name = "Weather")
public class WeatherController {

	private final FarmService farms;

	private final WeatherService weather;

	public WeatherController(FarmService farms, WeatherService weather) {
		this.farms = farms;
		this.weather = weather;
	}

	public record WeatherView(double latitude, double longitude, boolean locationApproximate, boolean live,
			LocalDate today, List<WeatherDay> days) {
	}

	@GetMapping
	@Operation(summary = "Daily weather (reference ET, rain, temperature) for recent days and the forecast")
	public WeatherView weather(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@RequestParam(defaultValue = "7") @Min(0) @Max(92) int pastDays,
			@RequestParam(defaultValue = "7") @Min(1) @Max(16) int forecastDays) {
		Farm farm = farms.requireAccessible(farmId, user);
		LocalDate today = weather.today(farm);
		WeatherSeries series = weather.series(farm, today.minusDays(pastDays), today.plusDays(forecastDays - 1));
		return new WeatherView(series.location().latitude(), series.location().longitude(),
				series.location().approximate(), series.live(), today, series.days());
	}

}
