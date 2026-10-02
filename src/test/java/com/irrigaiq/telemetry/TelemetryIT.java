package com.irrigaiq.telemetry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.irrigaiq.support.IntegrationTest;

import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TelemetryIT extends IntegrationTest {

	private static final ZoneId HARARE = ZoneId.of("Africa/Harare");

	@Test
	void devicesAuthenticateWithTheirOwnKeyAndProbeReadingsCalibrateTheField() throws Exception {
		Tokens farmer = register(uniqueEmail("devices"));
		String farmId = createFarm(farmer);
		String fieldId = createPlantedField(farmer, farmId);

		MvcResult registered = mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/devices"), """
				{"type":"SOIL_MOISTURE","deviceId":"esp32-block-a","name":"Probe A","fieldId":"%s"}"""
			.formatted(fieldId)), farmer.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.deviceKey", startsWith("iqd_")))
			.andExpect(jsonPath("$.device.fieldName").value("Block A"))
			.andReturn();
		String key = read(registered, "$.deviceKey");
		String deviceId = read(registered, "$.device.id");

		// The key is never shown again.
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/devices"), farmer.accessToken()))
			.andExpect(jsonPath("$[0].keyPrefix").value(key.substring(4, 14)))
			.andExpect(jsonPath("$[0].deviceKey").doesNotExist());

		mvc.perform(ingest(null, "{\"readings\":[{\"soilMoisturePct\":23}]}")).andExpect(status().isUnauthorized());
		mvc.perform(ingest("iqd_aaaaaaaaaa_" + "x".repeat(43), "{\"readings\":[{\"soilMoisturePct\":23}]}"))
			.andExpect(status().isUnauthorized());

		String at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
		String body = "{\"readings\":[{\"recordedAt\":\"%s\",\"soilMoisturePct\":23.0,\"temperatureC\":24.5}]}"
			.formatted(at);
		mvc.perform(ingest(key, body))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.accepted").value(1));
		// Devices retry: the same instant is stored once.
		mvc.perform(ingest(key, body))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.accepted").value(0))
			.andExpect(jsonPath("$.duplicates").value(1));

		// 23 % on the default scale (40 % full, 15 % wilting) is 68 % depleted, and the water balance restarts there.
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/fields/" + fieldId + "/soil-observations"),
				farmer.accessToken()))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].method").value("SENSOR"))
			.andExpect(jsonPath("$[0].depletionPct").value(68.0));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/irrigation/status"), farmer.accessToken()))
			.andExpect(jsonPath("$[0].depletionPct").value(greaterThan(60.0)))
			.andExpect(jsonPath("$[0].recommendation.decision").value("IRRIGATE_NOW"))
			.andExpect(jsonPath("$[0].recommendation.confidence").value("MEDIUM"));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/devices/" + deviceId), farmer.accessToken()))
			.andExpect(jsonPath("$.latest.soilMoisturePct").value(23.0))
			.andExpect(jsonPath("$.depletionPct").value(68.0))
			.andExpect(jsonPath("$.online").value(true));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/devices/" + deviceId + "/readings?hours=2"),
				farmer.accessToken()))
			.andExpect(jsonPath("$.hours", hasSize(1)))
			.andExpect(jsonPath("$.hours[0].soilMoisturePct", closeTo(23.0, 0.01)));

		// A disabled device is refused; rotating the key retires the old one.
		mvc.perform(bearer(json(put("/api/v1/farms/" + farmId + "/devices/" + deviceId), """
				{"name":"Probe A","fieldId":"%s","status":"DISABLED"}""".formatted(fieldId)), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DISABLED"));
		mvc.perform(ingest(key, "{\"readings\":[{\"soilMoisturePct\":30}]}")).andExpect(status().isForbidden());
		mvc.perform(bearer(json(put("/api/v1/farms/" + farmId + "/devices/" + deviceId), """
				{"name":"Probe A","fieldId":"%s","status":"ACTIVE"}""".formatted(fieldId)), farmer.accessToken()))
			.andExpect(status().isOk());
		String rotated = read(mvc.perform(bearer(post("/api/v1/farms/" + farmId + "/devices/" + deviceId
				+ "/rotate-key"), farmer.accessToken())).andExpect(status().isOk()).andReturn(), "$.deviceKey");
		mvc.perform(ingest(key, "{\"readings\":[{\"soilMoisturePct\":30}]}")).andExpect(status().isUnauthorized());
		mvc.perform(ingest(rotated, "{\"readings\":[{\"soilMoisturePct\":30}]}")).andExpect(status().isAccepted());

		// Another user cannot see the farm's devices.
		Tokens stranger = register(uniqueEmail("stranger"));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/devices"), stranger.accessToken()))
			.andExpect(status().isNotFound());
	}

	@Test
	void devicesAreValidatedForWhereTheyAreFittedAndWhenTheyReport() throws Exception {
		Tokens farmer = register(uniqueEmail("fitting"));
		String farmId = createFarm(farmer);
		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/devices"), """
				{"type":"SOIL_MOISTURE","deviceId":"probe-1"}"""), farmer.accessToken()))
			.andExpect(status().isBadRequest());
		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/devices"), """
				{"type":"PUMP_MONITOR","deviceId":"pump-1"}"""), farmer.accessToken()))
			.andExpect(status().isBadRequest());
		String fieldId = createPlantedField(farmer, farmId);
		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/devices"), """
				{"type":"SOIL_MOISTURE","deviceId":"probe-1","fieldId":"%s","fieldCapacityPct":20,"wiltingPointPct":30}"""
			.formatted(fieldId)), farmer.accessToken()))
			.andExpect(status().isBadRequest());

		String key = read(mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/devices"), """
				{"type":"FLOW_METER","deviceId":"meter-1","fieldId":"%s"}""".formatted(fieldId)),
				farmer.accessToken())).andExpect(status().isCreated()).andReturn(), "$.deviceKey");
		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/devices"), """
				{"type":"FLOW_METER","deviceId":"METER-1","fieldId":"%s"}""".formatted(fieldId)), farmer.accessToken()))
			.andExpect(status().isConflict());
		mvc.perform(ingest(key, "{\"readings\":[{\"recordedAt\":\"%s\",\"flowLpm\":10}]}"
			.formatted(Instant.now().plus(1, ChronoUnit.HOURS)))).andExpect(status().isBadRequest());
		mvc.perform(ingest(key, "{\"readings\":[{}]}")).andExpect(status().isBadRequest());
		mvc.perform(ingest(key, "{\"readings\":[{\"flowLpm\":-1}]}")).andExpect(status().isBadRequest());
	}

	@Test
	void machineLearningEndpointsDegradeGracefullyWhenTheMlServiceIsOff() throws Exception {
		Tokens farmer = register(uniqueEmail("ml-off"));
		String farmId = createFarm(farmer);
		String fieldId = createPlantedField(farmer, farmId);
		String meterKey = read(mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/devices"), """
				{"type":"FLOW_METER","deviceId":"meter-a","fieldId":"%s"}""".formatted(fieldId)),
				farmer.accessToken())).andExpect(status().isCreated()).andReturn(), "$.deviceKey");
		mvc.perform(ingest(meterKey, "{\"source\":\"SIMULATED\",\"readings\":[{\"flowLpm\":8.3}]}"))
			.andExpect(status().isAccepted());

		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/ml/irrigation"), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available").value(false))
			.andExpect(jsonPath("$.fields[0].status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.fields[0].inputs.growthPhase").value("MID_SEASON"));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/ml/water-anomalies"), farmer.accessToken()))
			.andExpect(jsonPath("$.available").value(false))
			.andExpect(jsonPath("$.meters[0].status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.meters[0].anomaly").value(nullValue()));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/ml/equipment-risk"), farmer.accessToken()))
			.andExpect(jsonPath("$.pumps", hasSize(0)));
		mvc.perform(bearer(get("/api/v1/ml/models"), farmer.accessToken()))
			.andExpect(jsonPath("$.available").value(false));
		// Alerts still evaluate on rules alone.
		mvc.perform(bearer(post("/api/v1/farms/" + farmId + "/alerts/evaluate"), farmer.accessToken()))
			.andExpect(status().isOk());
	}

	private MockHttpServletRequestBuilder ingest(String key, String body) {
		MockHttpServletRequestBuilder request = json(post("/api/v1/telemetry"), body);
		return (key != null) ? request.header("X-Device-Key", key) : request;
	}

	private String createFarm(Tokens owner) throws Exception {
		return read(mvc.perform(bearer(json(post("/api/v1/farms"), """
				{"name":"Sensor Farm","location":"Mazowe","latitude":-17.5,"longitude":30.97,"timezone":"Africa/Harare"}"""),
				owner.accessToken())).andExpect(status().isCreated()).andReturn(), "$.id");
	}

	private String createPlantedField(Tokens owner, String farmId) throws Exception {
		String fieldId = read(mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/fields"), """
				{"name":"Block A","areaHa":2.0,"soilType":"SANDY_LOAM","latitude":-17.50,"longitude":30.97}"""),
				owner.accessToken())).andExpect(status().isCreated()).andReturn(), "$.id");
		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/fields/" + fieldId + "/crops"), """
				{"cropType":"TOMATO","plantingDate":"%s","growthStage":"MID_SEASON","expectedYieldTHa":55}"""
			.formatted(LocalDate.now(HARARE).minusDays(75))), owner.accessToken())).andExpect(status().isCreated());
		return fieldId;
	}

}
