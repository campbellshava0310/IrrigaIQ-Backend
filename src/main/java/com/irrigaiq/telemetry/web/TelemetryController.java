package com.irrigaiq.telemetry.web;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.telemetry.Reading;
import com.irrigaiq.telemetry.ReadingSource;
import com.irrigaiq.telemetry.TelemetryService;
import com.irrigaiq.telemetry.TelemetryViews.IngestReceipt;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device ingestion. Authenticated by the device's own key in {@value #DEVICE_KEY_HEADER}, not by a
 * user's access token, so the path is public in the security configuration.
 */
@RestController
@Tag(name = "Telemetry")
public class TelemetryController {

	public static final String PATH = "/api/v1/telemetry";

	public static final String DEVICE_KEY_HEADER = "X-Device-Key";

	private final TelemetryService telemetry;

	public TelemetryController(TelemetryService telemetry) {
		this.telemetry = telemetry;
	}

	/** Omit {@code recordedAt} to use the server's clock (devices without a real-time clock). */
	public record ReadingRequest(Instant recordedAt, @DecimalMin("0") @DecimalMax("100") Double soilMoisturePct,
			@DecimalMin("-50") @DecimalMax("90") Double temperatureC,
			@DecimalMin("0") @DecimalMax("100") Double humidityPct,
			@DecimalMin("0") @DecimalMax("500") Double rainfallMm,
			@DecimalMin("0") @DecimalMax("100000") Double flowLpm,
			@DecimalMin("0") @DecimalMax("10000000") Double volumeLitres,
			@DecimalMin("0") @DecimalMax("100") Double pressureBar,
			@DecimalMin("0") @DecimalMax("5000") Double powerKw,
			@DecimalMin("0") @DecimalMax("200") Double vibrationMmS,
			@DecimalMin("-50") @DecimalMax("250") Double motorTempC) {

		Reading toReading() {
			return new Reading(recordedAt, soilMoisturePct, temperatureC, humidityPct, rainfallMm, flowLpm,
					volumeLitres, pressureBar, powerKw, vibrationMmS, motorTempC);
		}

	}

	/** @param source {@code SIMULATED} for test rigs and simulators, so synthetic data stays labelled */
	public record TelemetryRequest(ReadingSource source, @NotEmpty @Size(max = 500) List<@Valid ReadingRequest> readings) {
	}

	@PostMapping(PATH)
	@ResponseStatus(HttpStatus.ACCEPTED)
	@SecurityRequirements
	@Operation(summary = "Submit readings from a device (authenticated with its X-Device-Key)")
	public IngestReceipt ingest(@RequestHeader(name = DEVICE_KEY_HEADER, required = false) String deviceKey,
			@Valid @RequestBody TelemetryRequest body) {
		return telemetry.ingest(deviceKey, (body.source() != null) ? body.source() : ReadingSource.DEVICE,
				body.readings().stream().map(ReadingRequest::toReading).toList());
	}

}
