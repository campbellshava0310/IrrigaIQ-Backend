package com.irrigaiq.operations;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.irrigaiq.support.IntegrationTest;

import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperationsIT extends IntegrationTest {

	private static final ZoneId HARARE = ZoneId.of("Africa/Harare");

	@Test
	void fieldsGetWaterBalanceRecommendationsThatRespondToIrrigationAndSoilChecks() throws Exception {
		Tokens farmer = register(uniqueEmail("irrigator"));
		String farmId = createFarm(farmer);
		String fieldId = read(mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/fields"), """
				{"name":"Block A","areaHa":2.0,"soilType":"SANDY_LOAM","latitude":-17.50,"longitude":30.97}"""),
				farmer.accessToken())).andExpect(status().isCreated()).andReturn(), "$.id");

		// Not planted yet: the model says so instead of guessing.
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/irrigation/status"), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].recommendation.decision").value("NOT_PLANTED"));

		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/fields/" + fieldId + "/crops"), """
				{"cropType":"TOMATO","plantingDate":"%s","growthStage":"MID_SEASON","expectedYieldTHa":55}"""
			.formatted(LocalDate.now(HARARE).minusDays(75))), farmer.accessToken())).andExpect(status().isCreated());
		mvc.perform(bearer(json(put("/api/v1/farms/" + farmId + "/fields/" + fieldId + "/irrigation-setup"), """
				{"method":"DRIP","applicationRateMmH":3.0,"efficiency":0.9}"""), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.irrigationMethod").value("DRIP"));

		// 75 days of dry-season climatology with no irrigation: the crop needs water now.
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/irrigation/status"), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].recommendation.decision").value("IRRIGATE_NOW"))
			.andExpect(jsonPath("$[0].recommendation.volumeM3").value(greaterThan(0.0)))
			.andExpect(jsonPath("$[0].recommendation.reasons", not(hasSize(0))))
			.andExpect(jsonPath("$[0].recommendation.source").value("FAO56_WATER_BALANCE"));

		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/fields/" + fieldId + "/water-balance"), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.days", hasSize(greaterThan(30))))
			.andExpect(jsonPath("$.season.phase").value("MID_SEASON"))
			.andExpect(jsonPath("$.setup.method").value("DRIP"))
			.andExpect(jsonPath("$.weatherLive").value(false));

		// One tap plans the recommended irrigation...
		MvcResult planned = mvc.perform(bearer(post("/api/v1/farms/" + farmId + "/fields/" + fieldId
				+ "/irrigation/plan-recommended"), farmer.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PLANNED"))
			.andExpect(jsonPath("$.source").value("RECOMMENDATION"))
			.andReturn();
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/irrigation/status"), farmer.accessToken()))
			.andExpect(jsonPath("$[0].recommendation.decision").value("SCHEDULED"));

		// ...and confirming it refills the root zone.
		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/irrigation/events/" + read(planned, "$.id")
				+ "/complete"), "{}"), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"));

		// A manual log and a soil check are both accepted and show up in history.
		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/fields/" + fieldId + "/irrigation/events"), """
				{"status":"COMPLETED","startedAt":"%s","durationMinutes":60}"""
			.formatted(Instant.now().minus(3, ChronoUnit.DAYS))), farmer.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.grossDepthMm").value(3.0));
		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/fields/" + fieldId + "/soil-observations"), """
				{"depletionPct":15,"method":"FEEL","notes":"moist at 20 cm"}"""), farmer.accessToken()))
			.andExpect(status().isCreated());
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/irrigation/events")
			.param("from", LocalDate.now(HARARE).minusDays(7).toString())
			.param("to", LocalDate.now(HARARE).plusDays(7).toString()), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/irrigation/status"), farmer.accessToken()))
			.andExpect(jsonPath("$[0].depletionPct").value(15.0))
			.andExpect(jsonPath("$[0].recommendation.decision").value(not("IRRIGATE_NOW")));
	}

	@Test
	void degradingPumpRaisesAnAlertThatBecomesAWorkOrderAndServiceResetsIt() throws Exception {
		Tokens farmer = register(uniqueEmail("maintainer"));
		String farmId = createFarm(farmer);
		String pumpId = read(mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/assets"), """
				{"type":"PUMP","name":"P-2 Dam pump","criticality":"HIGH","powerSource":"GRID","ratedPowerKw":15,
				 "ratedFlowM3h":60,"installedOn":"2019-01-01","expectedLifeYears":15,"serviceIntervalDays":180,
				 "lastServiceOn":"%s","baselineEnergyKwhPerM3":0.22,"operatingWindowStart":"22:00:00",
				 "operatingWindowEnd":"05:00:00"}""".formatted(LocalDate.now(HARARE).minusDays(210))),
				farmer.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.health.service.overdue").value(true))
			.andReturn(), "$.id");

		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/assets/" + pumpId + "/condition-checks"), """
				{"overallCondition":"FAIR","flowM3h":54.6,"powerKw":14.8,"vibration":"ELEVATED"}"""),
				farmer.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.energyKwhPerM3").value(greaterThan(0.26)));

		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/assets/" + pumpId), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.asset.health.risk").value("CRITICAL"))
			.andExpect(jsonPath("$.asset.health.factors[*].code", hasItem("ENERGY_DRIFT")))
			.andExpect(jsonPath("$.recentChecks", hasSize(1)));

		// Alerts are evaluated after the commit; ask explicitly so the test does not depend on timing.
		mvc.perform(bearer(post("/api/v1/farms/" + farmId + "/alerts/evaluate"), farmer.accessToken()))
			.andExpect(status().isOk());
		MvcResult alerts = mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/alerts"), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].type").value("PUMP_EFFICIENCY_DROP"))
			.andExpect(jsonPath("$[0].severity").value("CRITICAL"))
			.andReturn();
		String alertId = read(alerts, "$[0].id");

		MvcResult link = mvc.perform(bearer(post("/api/v1/farms/" + farmId + "/alerts/" + alertId + "/work-order"),
				farmer.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.reference").value(notNullValue()))
			.andExpect(jsonPath("$.alert.state").value("ACKNOWLEDGED"))
			.andReturn();
		String workOrderId = read(link, "$.workOrderId");
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/work-orders/" + workOrderId), farmer.accessToken()))
			.andExpect(jsonPath("$.kind").value("PREDICTIVE"))
			.andExpect(jsonPath("$.priority").value("URGENT"))
			.andExpect(jsonPath("$.asset.id").value(pumpId));

		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/work-orders/" + workOrderId + "/complete"), """
				{"notes":"Replaced impeller wear rings","cost":180,"downtimeHours":5,"resetServiceInterval":true,
				 "restoreOperational":true}"""), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/assets/" + pumpId), farmer.accessToken()))
			.andExpect(jsonPath("$.asset.lastServiceOn").value(LocalDate.now(HARARE).toString()))
			.andExpect(jsonPath("$.asset.health.service.overdue").value(false));
	}

	@Test
	void dashboardBriefingAndWaterSummaryAreAvailableForAnyFarm() throws Exception {
		Tokens farmer = register(uniqueEmail("viewer"));
		String farmId = createFarm(farmer);
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/dashboard"), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.forecast", hasSize(7)))
			.andExpect(jsonPath("$.water.currency").value("USD"));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/briefing"), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headline").value(notNullValue()))
			.andExpect(jsonPath("$.method").value("RULES"));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/water/summary")
			.param("from", LocalDate.now(HARARE).minusDays(29).toString())
			.param("to", LocalDate.now(HARARE).toString()), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.daily", hasSize(30)));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/weather"), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.days", hasSize(14)))
			.andExpect(jsonPath("$.days[0].source").value("CLIMATOLOGY"));
	}

	@Test
	void theDemoFarmIsAdminOnlyAndComesWithScenarios() throws Exception {
		Tokens farmer = register(uniqueEmail("not-admin"));
		mvc.perform(bearer(post("/api/v1/admin/demo-farm"), farmer.accessToken())).andExpect(status().isForbidden());

		Tokens admin = loginAdmin();
		MvcResult demo = mvc.perform(bearer(post("/api/v1/admin/demo-farm"), admin.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.fields").value(4))
			.andExpect(jsonPath("$.assets").value(greaterThan(8)))
			.andExpect(jsonPath("$.irrigations").value(greaterThan(10)))
			.andReturn();
		String farmId = read(demo, "$.farmId");

		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/alerts"), admin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].type", hasItem("PUMP_EFFICIENCY_DROP")));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/dashboard"), admin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fields.total").value(4))
			.andExpect(jsonPath("$.tasks", not(hasSize(0))))
			.andExpect(jsonPath("$.assets.attention", not(hasSize(0))));

		// Other farm managers cannot see it.
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/assets"), farmer.accessToken()))
			.andExpect(status().isNotFound());
	}

	private String createFarm(Tokens owner) throws Exception {
		return read(mvc.perform(bearer(json(post("/api/v1/farms"), """
				{"name":"Ops Farm","location":"Mazowe","latitude":-17.5,"longitude":30.97,"electricityTariff":0.12}"""),
				owner.accessToken())).andExpect(status().isCreated()).andReturn(), "$.id");
	}

}
