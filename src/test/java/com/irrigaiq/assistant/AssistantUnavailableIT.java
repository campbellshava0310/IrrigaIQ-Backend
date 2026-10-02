package com.irrigaiq.assistant;

import org.junit.jupiter.api.Test;

import com.irrigaiq.support.IntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantUnavailableIT extends IntegrationTest {

	@Test
	void withoutAnApiKeyTheAssistantSaysSoInsteadOfFailing() throws Exception {
		Tokens farmer = register(uniqueEmail("no-key"));
		String farmId = read(mvc.perform(bearer(json(post("/api/v1/farms"), """
				{"name":"Keyless Farm","location":"Mazowe"}"""), farmer.accessToken()))
			.andExpect(status().isCreated())
			.andReturn(), "$.id");
		mvc.perform(bearer(get("/api/v1/farms/" + farmId + "/assistant"), farmer.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.configured").value(false))
			.andExpect(jsonPath("$.suggestions").isNotEmpty());
		mvc.perform(bearer(json(post("/api/v1/farms/" + farmId + "/assistant/chat"), """
				{"messages":[{"role":"user","content":"Hello"}]}"""), farmer.accessToken()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
	}

}
