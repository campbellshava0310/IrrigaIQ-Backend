package com.irrigaiq.assistant;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param provider which language model answers: the Claude API, or a local model served by Ollama
 * @param apiKey Claude API key; with the Claude provider the assistant is disabled (503) while empty
 * @param baseUrl alternative Claude API endpoint (an API gateway or proxy); empty for the API itself
 * @param model Claude model id
 * @param effort {@code low}, {@code medium}, {@code high}: depth of Claude's reasoning per answer
 * @param maxToolRounds tool-use round trips per answer before the assistant must answer
 * @param requestsPerHour per user, to bound cost (or, locally, CPU)
 * @param timeout for the whole answer, including every tool round
 */
@ConfigurationProperties("irrigaiq.assistant")
public record AssistantProperties(@DefaultValue("CLAUDE") Provider provider, @DefaultValue("") String apiKey,
		@DefaultValue("") String baseUrl, @DefaultValue("claude-opus-5") String model,
		@DefaultValue("medium") String effort, @DefaultValue("32000") long maxTokens,
		@DefaultValue("8") int maxToolRounds, @DefaultValue("40") int requestsPerHour,
		@DefaultValue("5m") Duration timeout, @DefaultValue Ollama ollama) {

	public enum Provider {

		CLAUDE, OLLAMA

	}

	/**
	 * A local model served by Ollama (https://ollama.com), for running the assistant with no data
	 * leaving the machine.
	 * @param contextTokens context window to allocate; memory grows with it (about 1 GB per 8k tokens
	 * for an 8B model)
	 * @param maxToolResultChars tool results are cut to this length so they fit the smaller context
	 */
	public record Ollama(@DefaultValue("http://localhost:11434") String baseUrl,
			@DefaultValue("llama3.1:8b") String model, @DefaultValue("12288") int contextTokens,
			@DefaultValue("0.2") double temperature, @DefaultValue("12000") int maxToolResultChars) {
	}

	public boolean configured() {
		return apiKey != null && !apiKey.isBlank();
	}

}
