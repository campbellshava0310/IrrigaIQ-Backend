package com.irrigaiq.weather;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FieldRepository;

import org.springframework.stereotype.Service;

/**
 * Daily weather for a farm. Live Open-Meteo data is cached per farm and refreshed every few
 * hours; anything the provider cannot supply (offline demo, outages, history older than 92 days)
 * is filled with deterministic climatology, clearly labelled as such.
 */
@Service
public class WeatherService {

	private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

	/** Harare: used when neither the farm nor its fields have coordinates. */
	public static final double DEFAULT_LATITUDE = -17.8292;

	public static final double DEFAULT_LONGITUDE = 31.0522;

	static final int MAX_PAST_DAYS = 92;

	static final int MAX_FORECAST_DAYS = 16;

	private final WeatherStore store;

	private final WeatherProvider provider;

	private final WeatherProperties properties;

	private final FieldRepository fields;

	private final Clock clock;

	private final Map<UUID, Instant> lastFailure = new ConcurrentHashMap<>();

	public WeatherService(WeatherStore store, WeatherProvider provider, WeatherProperties properties,
			FieldRepository fields, Clock clock) {
		this.store = store;
		this.provider = provider;
		this.properties = properties;
		this.fields = fields;
		this.clock = clock;
	}

	public record FarmLocation(double latitude, double longitude, boolean approximate) {
	}

	/**
	 * @param live whether every day the provider can cover came from live data
	 */
	public record WeatherSeries(List<WeatherDay> days, FarmLocation location, boolean live) {

		public WeatherDay on(LocalDate date) {
			return days.stream().filter((day) -> day.date().equals(date)).findFirst().orElse(null);
		}

	}

	/** "Today" in the farm's own time zone. */
	public LocalDate today(Farm farm) {
		return LocalDate.now(clock.withZone(farm.zone()));
	}

	public FarmLocation locate(Farm farm) {
		if (farm.getLatitude() != null && farm.getLongitude() != null) {
			return new FarmLocation(farm.getLatitude().doubleValue(), farm.getLongitude().doubleValue(), false);
		}
		FieldRepository.Coordinates centroid = fields.averageCoordinates(farm.getId());
		if (centroid != null && centroid.getLatitude() != null && centroid.getLongitude() != null) {
			return new FarmLocation(centroid.getLatitude(), centroid.getLongitude(), false);
		}
		return new FarmLocation(DEFAULT_LATITUDE, DEFAULT_LONGITUDE, true);
	}

	public WeatherSeries series(Farm farm, LocalDate from, LocalDate to) {
		LocalDate today = today(farm);
		FarmLocation location = locate(farm);
		LocalDate liveFrom = max(from, today.minusDays(MAX_PAST_DAYS));
		LocalDate liveTo = min(to, today.plusDays(MAX_FORECAST_DAYS - 1));

		Map<LocalDate, WeatherStore.Cached> cached = index(store.find(farm.getId(), from, to));
		if (properties.provider() == WeatherSource.OPEN_METEO && !liveTo.isBefore(liveFrom)
				&& needsRefresh(cached, liveFrom, liveTo, today) && !backingOff(farm.getId())) {
			// Use what was just fetched directly: it may not have been cached (see WeatherStore#upsert).
			Instant fetchedAt = clock.instant();
			for (WeatherDay day : refresh(farm, location, liveFrom, liveTo, today)) {
				if (!day.date().isBefore(from) && !day.date().isAfter(to)) {
					cached.put(day.date(), new WeatherStore.Cached(day, fetchedAt));
				}
			}
		}

		List<WeatherDay> days = new ArrayList<>();
		boolean live = true;
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			boolean forecast = !date.isBefore(today);
			WeatherStore.Cached hit = cached.get(date);
			if (hit != null) {
				days.add(hit.day().withForecast(forecast));
			}
			else {
				days.add(Climatology.day(location.latitude(), location.longitude(), date, forecast));
				if (!date.isBefore(liveFrom) && !date.isAfter(liveTo)) {
					live = false;
				}
			}
		}
		return new WeatherSeries(days, location, live);
	}

	/** Refreshes the live window (recent past + forecast) for a farm; used by the scheduled pre-warm. */
	public void prefetch(Farm farm) {
		LocalDate today = today(farm);
		series(farm, today.minusDays(MAX_PAST_DAYS), today.plusDays(MAX_FORECAST_DAYS - 1));
	}

	private boolean needsRefresh(Map<LocalDate, WeatherStore.Cached> cached, LocalDate liveFrom, LocalDate liveTo,
			LocalDate today) {
		Instant staleBefore = clock.instant().minus(properties.refreshInterval());
		for (LocalDate date = liveFrom; !date.isAfter(liveTo); date = date.plusDays(1)) {
			WeatherStore.Cached hit = cached.get(date);
			if (hit == null) {
				return true;
			}
			// Forecasts (and yesterday's provisional values) go stale; settled history does not.
			if (!date.isBefore(today.minusDays(1)) && hit.fetchedAt().isBefore(staleBefore)) {
				return true;
			}
		}
		return false;
	}

	/** @return the live days fetched, or none when the provider failed (then backs off for a while) */
	private List<WeatherDay> refresh(Farm farm, FarmLocation location, LocalDate liveFrom, LocalDate liveTo,
			LocalDate today) {
		int pastDays = (int) Math.max(1, Math.min(MAX_PAST_DAYS, ChronoUnit.DAYS.between(liveFrom, today)));
		int forecastDays = (int) Math.max(1,
				Math.min(MAX_FORECAST_DAYS, ChronoUnit.DAYS.between(today, liveTo) + 1));
		List<WeatherDay> fetched;
		try {
			fetched = provider.fetch(location.latitude(), location.longitude(), farm.getTimezone(), pastDays,
					forecastDays);
			lastFailure.remove(farm.getId());
		}
		catch (RuntimeException ex) {
			lastFailure.put(farm.getId(), clock.instant());
			log.warn("Weather refresh failed for farm {}; using climatology until retry: {}", farm.getId(),
					ex.getMessage());
			return List.of();
		}
		try {
			store.upsert(farm.getId(), fetched, clock.instant());
		}
		catch (RuntimeException ex) {
			// The forecast itself is fine and is used for this request; only the cache missed it.
			log.warn("Could not cache weather for farm {}: {}", farm.getId(), ex.getMessage());
		}
		return fetched;
	}

	private boolean backingOff(UUID farmId) {
		Instant failedAt = lastFailure.get(farmId);
		return failedAt != null && failedAt.plus(properties.retryAfterFailure()).isAfter(clock.instant());
	}

	private static Map<LocalDate, WeatherStore.Cached> index(List<WeatherStore.Cached> rows) {
		return rows.stream()
			.collect(Collectors.toMap((row) -> row.day().date(), Function.identity(), (a, b) -> b, HashMap::new));
	}

	private static LocalDate max(LocalDate a, LocalDate b) {
		return a.isAfter(b) ? a : b;
	}

	private static LocalDate min(LocalDate a, LocalDate b) {
		return a.isBefore(b) ? a : b;
	}

}
