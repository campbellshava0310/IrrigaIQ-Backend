package com.irrigaiq.weather;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmDetails;
import com.irrigaiq.farm.FarmRepository;
import com.irrigaiq.identity.UserRepository;
import com.irrigaiq.support.IntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The test profile runs on climatology, which is never cached, so exercise the cache directly. */
class WeatherStoreIT extends IntegrationTest {

	@Autowired
	private WeatherStore store;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private FarmRepository farms;

	@Autowired
	private UserRepository users;

	@Test
	void liveWeatherRoundTripsAndIsNeverOverwrittenByClimatology() throws Exception {
		Tokens farmer = register(uniqueEmail("weather"));
		MvcResult farm = mvc.perform(bearer(json(post("/api/v1/farms"), """
				{"name":"Weather Farm","latitude":-17.5,"longitude":30.97}"""), farmer.accessToken()))
			.andExpect(status().isCreated())
			.andReturn();
		UUID farmId = UUID.fromString(read(farm, "$.id"));
		LocalDate day = LocalDate.of(2026, 10, 1);

		store.upsert(farmId, List.of(new WeatherDay(day, 5.8, 12.4, 70, 29.5, 16.1, 14.2, WeatherSource.OPEN_METEO, true)),
				Instant.parse("2026-10-01T04:00:00Z"));
		store.upsert(farmId, List.of(new WeatherDay(day, 4.0, 0.0, 0, 25.0, 12.0, 10.0, WeatherSource.CLIMATOLOGY, true)),
				Instant.parse("2026-10-01T05:00:00Z"));

		List<WeatherStore.Cached> cached = store.find(farmId, day, day);
		assertThat(cached).singleElement().satisfies((row) -> {
			assertThat(row.day().source()).isEqualTo(WeatherSource.OPEN_METEO);
			assertThat(row.day().et0Mm()).isEqualTo(5.8);
			assertThat(row.day().precipitationMm()).isEqualTo(12.4);
			assertThat(row.day().precipitationProbability()).isEqualTo(70);
			assertThat(row.day().tempMinC()).isEqualTo(16.1);
		});
	}

	@Test
	void cachingIsSkippedWhileTheCallerIsStillWritingItsFarm() {
		LocalDate day = LocalDate.of(2026, 10, 2);
		WeatherDay live = new WeatherDay(day, 6.1, 0.0, 5, 31.0, 15.0, 18.4, WeatherSource.OPEN_METEO, true);

		transactions.executeWithoutResult((status) -> {
			Farm farm = farms.saveAndFlush(new Farm(users.findByEmail(ADMIN_EMAIL).orElseThrow(),
					FarmDetails.basic("Being set up", null, new BigDecimal("10"))));

			// A separate transaction could not reference the uncommitted farm; writing here could abort it.
			assertThat(store.upsert(farm.getId(), List.of(live), Instant.now())).isFalse();
			assertThat(store.find(farm.getId(), day, day)).isEmpty();
			assertThat(farms.findById(farm.getId())).isPresent(); // the set-up is unaffected
			status.setRollbackOnly();
		});
	}

}
