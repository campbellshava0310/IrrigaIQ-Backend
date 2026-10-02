package com.irrigaiq.assistant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@link ChatModel} on a local model served by Ollama (for example Llama 3.1 8B in Docker), so the
 * assistant runs with no data leaving the machine. Uses Ollama's native chat API: streamed
 * newline-delimited JSON with function calling.
 *
 * <p>Small open models are less disciplined than frontier models, so this adapter adds tool-use
 * guidance to the system prompt, caps tool results to fit a smaller context window, and recovers a
 * tool call the model wrote out as JSON text instead of calling the function.
 */
@Component
@ConditionalOnProperty(prefix = "irrigaiq.assistant", name = "provider", havingValue = "OLLAMA")
class OllamaChatModel implements ChatModel {

	private static final Logger log = LoggerFactory.getLogger(OllamaChatModel.class);

	/** Appended to the system prompt: local models need the tool protocol spelled out. */
	static final String LOCAL_MODEL_GUIDANCE = """
			# Using tools
			- When the answer depends on this farm's data, call the matching tool first. Never answer a \
			question about the farm from memory, and never invent numbers.
			- Call tools through the function-calling interface only; never write a tool call as text or JSON.
			- Leave optional parameters out unless the user named that specific field or asset.
			- If a tool returns an error, correct the input and call it again, or tell the user the data is \
			unavailable. Never invent numbers, percentages, dates or readings.
			- Once the results arrive, answer in plain sentences for a farm manager, with the numbers and units \
			exactly as the results give them. Do not mention tool names or JSON.
			""";

	/** Output cap per turn: on a CPU every token costs time, and answers should be short anyway. */
	static final int MAX_OUTPUT_TOKENS = 1536;

	static final Duration READINESS_CACHE = Duration.ofSeconds(15);

	private final AssistantProperties.Ollama settings;

	private final Duration timeout;

	private final ObjectMapper json;

	private final Clock clock;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private volatile Instant readinessCheckedAt = Instant.MIN;

	private volatile Optional<String> readinessProblem = Optional.empty();

	OllamaChatModel(AssistantProperties properties, ObjectMapper json, Clock clock) {
		this.settings = properties.ollama();
		this.timeout = properties.timeout();
		this.json = json;
		this.clock = clock;
	}

	@Override
	public boolean configured() {
		return true;
	}

	@Override
	public String modelName() {
		return settings.model();
	}

	@Override
	public String hostedBy() {
		return "on this server";
	}

	@Override
	public int maxToolResultChars() {
		return settings.maxToolResultChars();
	}

	/** Whether the Ollama server is up and has the model; cached briefly. */
	@Override
	public Optional<String> problem() {
		Instant now = clock.instant();
		if (readinessCheckedAt.plus(READINESS_CACHE).isAfter(now)) {
			return readinessProblem;
		}
		Optional<String> problem;
		try {
			HttpResponse<String> response = http.send(
					HttpRequest.newBuilder(uri("/api/tags")).timeout(Duration.ofSeconds(3)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			JsonNode models = json.readTree(response.body()).path("models");
			boolean present = false;
			for (JsonNode model : models) {
				String name = model.path("name").asString("");
				present |= name.equals(settings.model()) || name.equals(settings.model() + ":latest");
			}
			problem = present ? Optional.empty()
					: Optional.of("The local model " + settings.model()
							+ " is not downloaded yet. Run: docker compose exec ollama ollama pull " + settings.model());
		}
		catch (IOException | JacksonException ex) {
			problem = Optional.of("The local model server (Ollama) is not running at " + settings.baseUrl()
					+ ". Start it with: docker compose --profile ai up -d");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return readinessProblem;
		}
		readinessProblem = problem;
		readinessCheckedAt = now;
		return problem;
	}

	/** Loads the model into memory at startup, so the first question does not wait for it. */
	@EventListener(ApplicationReadyEvent.class)
	void warmUp() {
		String body = json.writeValueAsString(Map.of("model", settings.model(), "keep_alive", "30m"));
		http.sendAsync(HttpRequest.newBuilder(uri("/api/generate"))
			.timeout(Duration.ofMinutes(3))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build(), HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
				if (error != null || response.statusCode() != 200) {
					log.warn("Could not preload the local model {} ({}); it loads on the first question instead",
							settings.model(), (error != null) ? error.getMessage() : "HTTP " + response.statusCode());
				}
				else {
					log.info("Local model {} loaded", settings.model());
				}
			});
	}

	@Override
	public Conversation start(List<SystemBlock> system, List<ChatMessage> history, List<ToolSpec> tools) {
		List<Map<String, Object>> messages = new ArrayList<>();
		String prompt = system.stream().map(SystemBlock::text).collect(Collectors.joining("\n\n")) + "\n\n"
				+ LOCAL_MODEL_GUIDANCE;
		messages.add(message("system", prompt));
		for (ChatMessage turn : history) {
			messages.add(message((turn.role() == Role.USER) ? "user" : "assistant", turn.content()));
		}
		List<Map<String, Object>> toolDefinitions = tools.stream()
			.map((tool) -> Map.<String, Object>of("type", "function", "function",
					Map.of("name", tool.name(), "description", tool.description(), "parameters", tool.inputSchema())))
			.toList();
		Set<String> toolNames = tools.stream().map(ToolSpec::name).collect(Collectors.toSet());
		return new OllamaConversation(messages, toolDefinitions, toolNames);
	}

	private URI uri(String path) {
		return URI.create(settings.baseUrl().replaceAll("/+$", "") + path);
	}

	private static Map<String, Object> message(String role, String content) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("role", role);
		message.put("content", content);
		return message;
	}

	private final class OllamaConversation implements Conversation {

		private final List<Map<String, Object>> messages;

		private final List<Map<String, Object>> tools;

		private final Set<String> toolNames;

		/** Tool-call id -> tool name, for labelling the results of the last turn. */
		private final Map<String, String> pendingCalls = new LinkedHashMap<>();

		private volatile boolean closed;

		private volatile Stream<String> current;

		OllamaConversation(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Set<String> toolNames) {
			this.messages = messages;
			this.tools = tools;
			this.toolNames = toolNames;
		}

		@Override
		public Turn next(TurnListener listener) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("model", settings.model());
			body.put("messages", messages);
			body.put("tools", tools);
			body.put("stream", true);
			body.put("keep_alive", "30m");
			body.put("options", Map.of("num_ctx", settings.contextTokens(), "temperature", settings.temperature(),
					"num_predict", MAX_OUTPUT_TOKENS));
			HttpRequest request = HttpRequest.newBuilder(uri("/api/chat"))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
				.build();

			HttpResponse<Stream<String>> response;
			try {
				response = http.send(request, HttpResponse.BodyHandlers.ofLines());
			}
			catch (IOException ex) {
				throw new UnavailableException("Could not reach the local model at " + settings.baseUrl()
						+ ". Is Ollama running? (docker compose --profile ai up -d)", ex);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new CancellationException("Interrupted");
			}
			if (response.statusCode() != 200) {
				String detail;
				try (Stream<String> lines = response.body()) {
					detail = lines.collect(Collectors.joining(" "));
				}
				throw new UnavailableException(errorMessage(response.statusCode(), detail), null);
			}

			StringBuilder text = new StringBuilder();
			List<ToolCall> calls = new ArrayList<>();
			boolean decided = false;
			boolean holding = false;
			boolean thinking = false;
			String doneReason = null;
			long input = 0;
			long output = 0;
			try (Stream<String> lines = response.body()) {
				current = lines;
				Iterator<String> iterator = lines.iterator();
				while (iterator.hasNext()) {
					if (closed) {
						throw new CancellationException("Conversation closed");
					}
					String line = iterator.next();
					if (line.isBlank()) {
						continue;
					}
					JsonNode chunk = json.readTree(line);
					if (chunk.has("error")) {
						throw new UnavailableException("The local model failed: " + chunk.path("error").asString(""),
								null);
					}
					JsonNode message = chunk.path("message");
					if (!thinking && !message.path("thinking").asString("").isEmpty()) {
						thinking = true;
						listener.onThinking();
					}
					String delta = message.path("content").asString("");
					if (!delta.isEmpty()) {
						text.append(delta);
						if (!decided && !delta.isBlank()) {
							decided = true;
							String start = text.toString().stripLeading();
							// A reply that opens like JSON may be a tool call written as text: hold it back.
							holding = start.startsWith("{") || start.startsWith("[") || start.startsWith("<|python_tag|>");
							if (!holding) {
								listener.onText(text.toString().stripLeading());
							}
						}
						else if (decided && !holding) {
							listener.onText(delta);
						}
					}
					for (JsonNode call : message.path("tool_calls")) {
						ToolCall toolCall = toolCall(call.path("function"), call.path("id").asString(""));
						calls.add(toolCall);
						listener.onToolCallStarted(toolCall.id(), toolCall.name());
					}
					if (chunk.path("done").asBoolean(false)) {
						doneReason = chunk.path("done_reason").asString(null);
						input = chunk.path("prompt_eval_count").asLong(0);
						output = chunk.path("eval_count").asLong(0);
					}
				}
			}
			catch (JacksonException ex) {
				throw new UnavailableException("The local model sent a response that could not be read", ex);
			}
			finally {
				current = null;
			}
			if (closed) {
				throw new CancellationException("Conversation closed");
			}

			String visible = text.toString().strip();
			if (holding) {
				ToolCall written = calls.isEmpty() ? writtenToolCall(visible) : null;
				if (written != null) {
					calls.add(written);
					listener.onToolCallStarted(written.id(), written.name());
					visible = "";
				}
				else if (!visible.isEmpty()) {
					listener.onText(visible);
				}
			}

			Map<String, Object> reply = message("assistant", visible);
			if (!calls.isEmpty()) {
				reply.put("tool_calls", calls.stream()
					.map((call) -> Map.of("function",
							Map.of("name", call.name(), "arguments", (call.input() != null) ? call.input() : Map.of())))
					.toList());
			}
			messages.add(reply);
			pendingCalls.clear();
			calls.forEach((call) -> pendingCalls.put(call.id(), call.name()));

			StopKind stop = !calls.isEmpty() ? StopKind.TOOL_USE
					: "length".equals(doneReason) ? StopKind.MAX_TOKENS : StopKind.END_TURN;
			return new Turn(stop, visible, List.copyOf(calls), new Usage(input, output, 0, 0), settings.model());
		}

		@Override
		public void addToolResults(List<ToolResult> results) {
			for (ToolResult result : results) {
				Map<String, Object> message = message("tool", result.content());
				message.put("tool_name", pendingCalls.getOrDefault(result.toolUseId(), ""));
				messages.add(message);
			}
		}

		@Override
		public void close() {
			closed = true;
			Stream<String> stream = current;
			if (stream != null) {
				stream.close();
			}
		}

		@SuppressWarnings("unchecked")
		private ToolCall toolCall(JsonNode function, String id) {
			String name = function.path("name").asString("");
			JsonNode arguments = function.path("arguments");
			Map<String, Object> input;
			try {
				input = arguments.isString() ? json.readValue(arguments.asString(), Map.class)
						: arguments.isObject() ? json.treeToValue(arguments, Map.class) : new LinkedHashMap<>();
			}
			catch (JacksonException ex) {
				input = null; // answered with an INVALID_JSON error so the model can retry
			}
			return new ToolCall(id.isBlank() ? "call_" + UUID.randomUUID() : id, name, input, arguments.toString());
		}

		/**
		 * Llama models sometimes print {@code {"name": "...", "parameters": {...}}} instead of calling
		 * the function. When the text is exactly that, for a tool that exists, treat it as the call.
		 */
		@SuppressWarnings("unchecked")
		private ToolCall writtenToolCall(String text) {
			String candidate = text.replace("<|python_tag|>", "").strip();
			if (candidate.startsWith("[") && candidate.endsWith("]")) {
				candidate = candidate.substring(1, candidate.length() - 1).strip();
			}
			try {
				Map<String, Object> parsed = json.readValue(candidate, Map.class);
				Object name = parsed.get("name");
				Object arguments = parsed.containsKey("parameters") ? parsed.get("parameters") : parsed.get("arguments");
				if (name instanceof String tool && toolNames.contains(tool)
						&& (arguments == null || arguments instanceof Map)) {
					Map<String, Object> input = (arguments != null) ? (Map<String, Object>) arguments : Map.of();
					return new ToolCall("call_" + UUID.randomUUID(), tool, new LinkedHashMap<>(input),
							json.writeValueAsString(input));
				}
			}
			catch (JacksonException ex) {
				// not a tool call after all: shown as text
			}
			return null;
		}

	}

	private String errorMessage(int status, String detail) {
		String error = detail;
		try {
			error = json.readTree(detail).path("error").asString(detail);
		}
		catch (JacksonException ex) {
			// plain-text error body
		}
		if (status == 404 || error.contains("not found")) {
			return "The local model " + settings.model() + " is not downloaded yet. Run: docker compose exec ollama "
					+ "ollama pull " + settings.model();
		}
		if (error.contains("does not support tools")) {
			return "The local model " + settings.model() + " cannot call tools. Use a tool-capable model such as "
					+ "llama3.1:8b (OLLAMA_MODEL).";
		}
		return "The local model failed (HTTP " + status + "): " + error;
	}

}
