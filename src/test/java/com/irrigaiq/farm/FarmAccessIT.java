package com.irrigaiq.farm;

import org.junit.jupiter.api.Test;

import com.irrigaiq.support.IntegrationTest;

import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FarmAccessIT extends IntegrationTest {

	@Test
	void ownerManagesFarmFieldAndCrop() throws Exception {
		Tokens owner = register(uniqueEmail("owner"));
		String farmId = createFarm(owner, "Mazowe Estate");

		MvcResult field = mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/fields"), """
				{"name":"Field 01","areaHa":4.2,"soilType":"SANDY_LOAM","latitude":-17.3912,"longitude":31.0735}"""),
				owner.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.areaHa").value(4.20))
			.andReturn();
		String fieldId = read(field, "$.id");

		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/fields/" + fieldId + "/crops"), """
				{"cropType":"TOMATO","variety":"Roma","plantingDate":"2026-08-15","growthStage":"DEVELOPMENT"}"""),
				owner.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.growthStage").value("DEVELOPMENT"));

		mvc.perform(bearer(get("/api/v1/farms/" + farmId), owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fieldCount").value(1));

		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/fields"), """
				{"name":"FIELD 01"}"""), owner.accessToken()))
			.andExpect(status().isConflict());

		mvc.perform(bearer(json(put("/api/v1/farms/" + farmId), """
				{"name":"Mazowe Estate North","location":"Mazowe","areaHa":130}"""), owner.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Mazowe Estate North"));

		mvc.perform(bearer(delete("/api/v1/farms/" + farmId), owner.accessToken())).andExpect(status().isNoContent());
		mvc.perform(bearer(get("/api/v1/farms/" + farmId), owner.accessToken())).andExpect(status().isNotFound());
	}

	@Test
	void farmsAreInvisibleToOtherFarmManagersButVisibleToAdmins() throws Exception {
		Tokens owner = register(uniqueEmail("owner"));
		Tokens stranger = register(uniqueEmail("stranger"));
		String farmId = createFarm(owner, "Private Farm");

		mvc.perform(bearer(get("/api/v1/farms/" + farmId), stranger.accessToken()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/fields"), stranger.accessToken()))
			.andExpect(status().isNotFound());
		mvc.perform(bearer(delete("/api/v1/farms/" + farmId), stranger.accessToken()))
			.andExpect(status().isNotFound());
		mvc.perform(bearer(get("/api/v1/farms"), stranger.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));

		mvc.perform(bearer(get("/api/v1/farms/" + farmId), loginAdmin().accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.owner.id").value(owner.userId()));
	}

	@Test
	void invalidInputIsRejected() throws Exception {
		Tokens owner = register(uniqueEmail("invalid"));
		mvc.perform(bearer(json(post("/api/v1/farms"), """
				{"name":"","areaHa":-3}"""), owner.accessToken()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.length()").value(2));
		mvc.perform(bearer(get("/api/v1/farms").param("sort", "nonexistent"), owner.accessToken()))
			.andExpect(status().isBadRequest());
	}

	private String createFarm(Tokens owner, String name) throws Exception {
		MvcResult result = mvc.perform(bearer(json(post("/api/v1/farms"), """
				{"name":"%s","location":"Mashonaland Central","areaHa":120.5}""".formatted(name)), owner.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.fieldCount").value(0))
			.andReturn();
		return read(result, "$.id");
	}

}
