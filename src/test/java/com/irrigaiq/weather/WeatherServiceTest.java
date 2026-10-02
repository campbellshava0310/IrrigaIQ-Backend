package com.irrigaiq.weather;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FieldRepository;

import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeatherServiceTest {

	private static final ZoneId HARARE = ZoneId.of("Africa/Harare");

	private final Clock clock = Clock.fixed(Instant.parse("2026-09-25T08:00:00Z"), ZoneOffset.UTC);

	private final LocalDate today = LocalDate.of(2026, 9, 25);

	private final WeatherStore store = mock(WeatherStore.class);

	private final Farm farm = mock(Farm.class);

	private final AtomicInteger providerCalls = new AtomicInteger();

	@BeforeEach
	void setUp() {
		when(farm.getId()).thenReturn(UUID.randomUUID());
		when(farm.getLatitude()).thenReturn(new BigDecimal("-17.5"));
		when(farm.getLongitude()).thenReturn(new BigDecimal("30.97"));
		when(farm.getTimezone()).thenReturn(HARARE.getId());
		when(farm.zone()).thenReturn(HARARE);
		when(store.find(any(), any(), any())).thenReturn(List.of());
	}

	@Test
	void aForecastThatCannotBeCachedYetIsStillUsed() {
		when(store.upsert(any(), anyList(), any())).thenReturn(false); // caller still writing its farm

		WeatherService.WeatherSeries series = service(liveProvider()).series(farm, today.minusDays(7), today.plusDays(6));

		assertThat(series.live()).isTrue();
		assertThat(series.days()).hasSize(14).allMatch((day) -> day.source() == WeatherSource.OPEN_METEO);
	}

	@Test
	void aCacheFailureDoesNotDiscardTheForecast() {
		when(store.upsert(any(), anyList(), any())).thenThrow(new DataAccessResourceFailureException("db down"));

		WeatherService service = service(liveProvider());
		assertThat(service.series(farm, today, today.plusDays(6)).live()).isTrue();
		// Only the provider's failures trigger the back-off; the next read tries again.
		service.series(farm, today, today.plusDays(6));
		assertThat(providerCalls).hasValue(2);
	}

	@Test
	void anUnreachableProviderFallsBackToClimatologyAndBacksOff() {
		WeatherService service = service((lat, lon, zone, past, forecast) -> {
			providerCalls.incrementAndGet();
			throw new IllegalStateException("timeout");
		});

		WeatherService.WeatherSeries series = service.series(farm, today, today.plusDays(6));
		service.series(farm, today, today.plusDays(6));

		assertThat(series.live()).isFalse();
		assertThat(series.days()).allMatch((day) -> day.source() == WeatherSource.CLIMATOLOGY);
		assertThat(providerCalls).hasValue(1);
	}

	private WeatherService service(WeatherProvider provider) {
		WeatherProperties properties = new WeatherProperties(WeatherSource.OPEN_METEO, "http://unused",
				Duration.ofHours(3), Duration.ofMinutes(10), Duration.ofSeconds(6));
		return new WeatherService(store, provider, properties, mock(FieldRepository.class), clock);
	}

	private WeatherProvider liveProvider() {
		return (lat, lon, zone, pastDays, forecastDays) -> {
			providerCalls.incrementAndGet();
			List<WeatherDay> days = new ArrayList<>();
			for (LocalDate day = today.minusDays(pastDays); day.isBefore(today.plusDays(forecastDays)); day = day.plusDays(1)) {
				days.add(new WeatherDay(day, 5.5, 0.0, 10, 29.0, 14.0, 11.0, WeatherSource.OPEN_METEO, !day.isBefore(today)));
			}
			return days;
		};
	}

}
