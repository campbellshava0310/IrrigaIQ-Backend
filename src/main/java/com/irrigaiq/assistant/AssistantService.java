package com.irrigaiq.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.irrigaiq.assistant.ChatModel.ChatMessage;
import com.irrigaiq.assistant.ChatModel.Conversation;
import com.irrigaiq.assistant.ChatModel.Role;
import com.irrigaiq.assistant.ChatModel.StopKind;
import com.irrigaiq.assistant.ChatModel.SystemBlock;
import com.irrigaiq.assistant.ChatModel.ToolCall;
import com.irrigaiq.assistant.ChatModel.ToolResult;
import com.irrigaiq.assistant.ChatModel.Turn;
import com.irrigaiq.assistant.ChatModel.TurnListener;
import com.irrigaiq.assistant.ChatModel.Usage;
import com.irrigaiq.assistant.FarmTools.ToolException;
import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.irrigation.IrrigationService;
import com.irrigaiq.knowledge.KnowledgeDocument.Source;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The assistant's tool loop. The model reasons and writes; the farm's data comes from
 * {@link FarmTools}, run with the asking user's permissions; ML predictions come from the ML
 * service through those tools, so the language model explains the platform's models rather than
 * replacing them. Everything the user sees is streamed as events.
 */
@Service
public class AssistantService {

	private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

	static final int MAX_MESSAGES = 40;

	static final int MAX_MESSAGE_CHARS = 4000;

	private final ChatModel model;

	private final FarmTools tools;

	private final FarmService farms;

	private final IrrigationService irrigation;

	private final AssistantProperties properties;

	private final AuditService audit;

	private final ObjectMapper json;

	private final Clock clock;

	private final Map<UUID, Deque<Instant>> recentRequests = new ConcurrentHashMap<>();

	public AssistantService(ChatModel model, FarmTools tools, FarmService farms, IrrigationService irrigation,
			AssistantProperties properties, AuditService audit, ObjectMapper json, Clock clock) {
		this.model = model;
		this.tools = tools;
		this.farms = farms;
		this.irrigation = irrigation;
		this.properties = properties;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
	}

	/** Receives the stream of events for one answer. */
	public interface EventSink {

		/** @throws ClientGoneException when the client has disconnected */
		void send(String event, Object data);

	}

	/** The client closed the connection; stop working on its answer. */
	public static final class ClientGoneException extends RuntimeException {

		public ClientGoneException(Throwable cause) {
			super("Client disconnected", cause);
		}

	}

	public record Message(String role, String content) {
	}

	/** A validated request, ready to run; {@link #cancel()} stops it from any thread. */
	public static final class Prepared {

		final Farm farm;

		final AuthenticatedUser user;

		final List<ChatMessage> history;

		private final AtomicBoolean cancelled = new AtomicBoolean();

		private final AtomicReference<Conversation> conversation = new AtomicReference<>();

		Prepared(Farm farm, AuthenticatedUser user, List<ChatMessage> history) {
			this.farm = farm;
			this.user = user;
			this.history = history;
		}

		public void cancel() {
			cancelled.set(true);
			Conversation current = conversation.get();
			if (current != null) {
				current.close();
			}
		}

		boolean cancelled() {
			return cancelled.get();
		}

	}

	/**
	 * @param provider {@code CLAUDE} or {@code OLLAMA}
	 * @param hostedBy where the model runs, for display ("Claude API", "on this server")
	 * @param problem why the model cannot answer right now (e.g. the local model is not downloaded)
	 */
	public record Status(boolean configured, String provider, String model, String hostedBy, String problem,
			List<String> suggestions) {
	}

	public Status status(UUID farmId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		return new Status(model.configured(), properties.provider().name(), model.modelName(), model.hostedBy(),
				model.problem().orElse(null), suggestions(farm));
	}

	/**
	 * Checks everything that can fail before streaming starts (configuration, access, rate limit,
	 * input), so those failures are ordinary HTTP errors rather than events mid-stream.
	 */
	public Prepared prepare(UUID farmId, AuthenticatedUser user, List<Message> messages) {
		if (!model.configured()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
					"IrrigaIQ chat is not switched on for this workspace yet");
		}
		Farm farm = farms.requireAccessible(farmId, user);
		model.problem().ifPresent((problem) -> {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, problem);
		});
		List<ChatMessage> history = validate(messages);
		throttle(user.id());
		audit.record(AuditEntry.of(AuditAction.ASSISTANT_CHAT)
			.target("FARM", farmId)
			.detail("messages", history.size())
			.detail("model", model.modelName()));
		return new Prepared(farm, user, history);
	}

	/** Runs the tool loop to a final answer, sending events as it goes. Blocks; call off the request thread. */
	public void run(Prepared request, EventSink sink) {
		Instant started = clock.instant();
		Instant deadline = started.plus(properties.timeout());
		Map<String, Source> sources = new LinkedHashMap<>();
		FarmTools.Context context = new FarmTools.Context(request.farm, request.user, sources);
		Set<String> toolsUsed = new LinkedHashSet<>();
		Usage usage = Usage.NONE;
		String servedBy = model.modelName();
		StopKind finalStop = StopKind.OTHER;
		int rounds = 0;

		List<SystemBlock> system = List.of(new SystemBlock(AssistantPrompt.RULES, true),
				new SystemBlock(AssistantPrompt.context(request.farm, request.user,
						irrigation.status(request.farm.getId(), request.user), clock.instant()), false));
		try (Conversation conversation = model.start(system, request.history, tools.specs())) {
			request.conversation.set(conversation);
			if (request.cancelled()) {
				return;
			}
			sink.send("start", Map.of("model", model.modelName()));
			while (true) {
				rounds++;
				Turn turn = conversation.next(listener(sink));
				usage = usage.plus(turn.usage());
				servedBy = turn.model();
				finalStop = turn.stop();
				if (turn.stop() == StopKind.REFUSAL) {
					sink.send("error", Map.of("code", "REFUSED", "message",
							"I can't help with that request. Try asking about your farm's water, crops or equipment."));
					break;
				}
				if (turn.stop() == StopKind.MAX_TOKENS) {
					// A truncated turn may hold a truncated tool call: never run it.
					sink.send("notice", Map.of("message", "The answer was cut short. Ask me to continue."));
					break;
				}
				if (turn.stop() != StopKind.TOOL_USE || turn.toolCalls().isEmpty()) {
					break;
				}
				boolean lastRound = rounds >= properties.maxToolRounds() || clock.instant().isAfter(deadline);
				List<ToolResult> results = new ArrayList<>();
				for (ToolCall call : turn.toolCalls()) {
					toolsUsed.add(call.name());
					results.add(lastRound ? budgetSpent(call, sink) : execute(call, context, sink));
				}
				conversation.addToolResults(results);
				if (rounds > properties.maxToolRounds()) {
					sink.send("notice", Map.of("message", "I stopped after gathering a lot of data. Ask a narrower question."));
					break;
				}
			}
			if (!sources.isEmpty()) {
				sink.send("sources", Map.of("sources", sources.values()));
			}
			sink.send("done", Map.of("stopReason", finalStop.name(), "model", servedBy, "rounds", rounds, "usage",
					Map.of("inputTokens", usage.inputTokens(), "outputTokens", usage.outputTokens(), "cacheReadTokens",
							usage.cacheReadTokens())));
		}
		catch (ClientGoneException | CancellationException ex) {
			log.debug("Assistant answer abandoned: the client disconnected");
		}
		catch (RuntimeException ex) {
			failed(ex, sink);
		}
		finally {
			log.info("Assistant answer: farm={} rounds={} tools={} stop={} model={} tokens(in={}, out={}, cacheRead={}) in {} ms",
					request.farm.getId(), rounds, toolsUsed, finalStop, servedBy, usage.inputTokens(),
					usage.outputTokens(), usage.cacheReadTokens(),
					Duration.between(started, clock.instant()).toMillis());
		}
	}

	private TurnListener listener(EventSink sink) {
		return new TurnListener() {

			private boolean thinkingAnnounced;

			@Override
			public void onText(String delta) {
				sink.send("text", Map.of("delta", delta));
			}

			@Override
			public void onThinking() {
				if (!thinkingAnnounced) {
					thinkingAnnounced = true;
					sink.send("status", Map.of("state", "thinking"));
				}
			}

			@Override
			public void onToolCallStarted(String id, String name) {
				sink.send("tool", Map.of("id", id, "name", name, "label", FarmTools.label(name), "state", "running"));
			}

		};
	}

	private ToolResult execute(ToolCall call, FarmTools.Context context, EventSink sink) {
		ChatModel.ToolSpec spec = tools.specs().stream().filter((s) -> s.name().equals(call.name())).findFirst().orElse(null);
		if (spec == null) {
			return error(call, sink, "Unknown tool " + call.name());
		}
		if (call.input() == null) {
			sink.send("tool", toolEvent(call, "error"));
			return new ToolResult(call.id(), "{\"INVALID_JSON\": " + quote(call.rawInput()) + "}", true);
		}
		Map<String, Object> input = withoutPlaceholders(spec, call.input());
		List<String> problems = ToolInputValidator.validate(spec.inputSchema(), input);
		if (!problems.isEmpty()) {
			return error(call, sink, "Invalid input: " + String.join("; ", problems));
		}
		try {
			String result = json.writeValueAsString(tools.run(call.name(), input, context));
			if (result.length() > model.maxToolResultChars()) {
				result = result.substring(0, model.maxToolResultChars()) + "... [truncated]";
			}
			sink.send("tool", toolEvent(call, "done"));
			return new ToolResult(call.id(), result, false);
		}
		catch (ToolException | ApiException ex) {
			return error(call, sink, ex.getMessage());
		}
		catch (ClientGoneException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			log.warn("Assistant tool {} failed", call.name(), ex);
			return error(call, sink, "The tool failed unexpectedly; answer without it and say the data is unavailable.");
		}
	}

	private ToolResult budgetSpent(ToolCall call, EventSink sink) {
		sink.send("tool", toolEvent(call, "skipped"));
		return new ToolResult(call.id(),
				"Not run: the data-gathering budget for this answer is used up. Answer now with what you have.", true);
	}

	private ToolResult error(ToolCall call, EventSink sink, String message) {
		log.debug("Assistant tool {} refused input {}: {}", call.name(), call.rawInput(), message);
		sink.send("tool", toolEvent(call, "error"));
		return new ToolResult(call.id(), "Error: " + message + " Correct the input and call the tool again, or tell "
				+ "the user this data could not be retrieved. Do not guess any numbers.", true);
	}

	/** Words smaller models put in an optional parameter when they mean "no filter". */
	private static final Set<String> PLACEHOLDERS = Set.of("", "all", "any", "none", "null", "n/a", "*", "every",
			"everything");

	/**
	 * Drops optional string parameters holding a placeholder ("all", "", "none"): smaller models fill
	 * optional filters instead of leaving them out, which would otherwise match nothing.
	 */
	@SuppressWarnings("unchecked")
	static Map<String, Object> withoutPlaceholders(ChatModel.ToolSpec spec, Map<String, Object> input) {
		List<String> required = (List<String>) spec.inputSchema().getOrDefault("required", List.of());
		Map<String, Object> cleaned = new LinkedHashMap<>(input);
		cleaned.entrySet()
			.removeIf((entry) -> !required.contains(entry.getKey()) && (entry.getValue() == null
					|| (entry.getValue() instanceof String text && PLACEHOLDERS.contains(text.strip().toLowerCase()))));
		return cleaned;
	}

	private static Map<String, Object> toolEvent(ToolCall call, String state) {
		return Map.of("id", call.id(), "name", call.name(), "label", FarmTools.label(call.name()), "state", state);
	}

	private void failed(RuntimeException ex, EventSink sink) {
		String message;
		String code;
		if (ex instanceof ChatModel.UnavailableException) {
			// The detail names the model and the server's setup: for the log, not for farmers.
			log.warn("Assistant model unavailable: {}", ex.getMessage());
			code = "UNAVAILABLE";
			message = "IrrigaIQ is unavailable right now. Try again shortly.";
		}
		else if (ex instanceof RateLimitException) {
			code = "BUSY";
			message = "IrrigaIQ is busy right now. Try again in a minute.";
		}
		else if (ex instanceof UnauthorizedException || ex instanceof PermissionDeniedException) {
			log.error("The Claude API rejected the assistant's API key: {}", ex.getMessage());
			code = "UNAVAILABLE";
			message = "IrrigaIQ is unavailable right now. Tell your administrator.";
		}
		else if (ex instanceof AnthropicIoException) {
			log.warn("Could not reach the Claude API: {}", ex.getMessage());
			code = "UNAVAILABLE";
			message = "Could not reach the AI service. Check the connection and try again.";
		}
		else if (ex instanceof AnthropicServiceException service) {
			log.warn("Claude API error {}: {}", service.statusCode(), ex.getMessage());
			code = "UNAVAILABLE";
			message = "The AI service returned an error. Try again shortly.";
		}
		else {
			log.error("Assistant failed", ex);
			code = "INTERNAL";
			message = "Something went wrong while answering. Try again.";
		}
		try {
			sink.send("error", Map.of("code", code, "message", message));
		}
		catch (ClientGoneException ignored) {
			// nobody left to tell
		}
	}

	private List<ChatMessage> validate(List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "Send at least one message");
		}
		if (messages.size() > MAX_MESSAGES) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
					"This conversation is too long; start a new one (at most " + MAX_MESSAGES + " messages)");
		}
		List<ChatMessage> out = new ArrayList<>();
		for (Message message : messages) {
			Role role = "user".equals(message.role()) ? Role.USER : "assistant".equals(message.role()) ? Role.ASSISTANT : null;
			if (role == null) {
				throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "Message roles are 'user' or 'assistant'");
			}
			String content = (message.content() != null) ? message.content().strip() : "";
			if (content.isEmpty() || content.length() > MAX_MESSAGE_CHARS) {
				throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
						"Each message needs 1-" + MAX_MESSAGE_CHARS + " characters");
			}
			if (!out.isEmpty() && out.get(out.size() - 1).role() == role) {
				// Merge consecutive turns of the same role (e.g. a retried question) into one.
				ChatMessage previous = out.remove(out.size() - 1);
				content = previous.content() + "\n\n" + content;
			}
			out.add(new ChatMessage(role, content));
		}
		if (out.get(0).role() != Role.USER || out.get(out.size() - 1).role() != Role.USER) {
			throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
					"A conversation starts and ends with the user's message");
		}
		return out;
	}

	/** Sliding one-hour window per user. */
	private void throttle(UUID userId) {
		Instant now = clock.instant();
		Deque<Instant> window = recentRequests.computeIfAbsent(userId, (id) -> new ArrayDeque<>());
		synchronized (window) {
			while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
				window.pollFirst();
			}
			if (window.size() >= properties.requestsPerHour()) {
				throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS,
						"You have reached the assistant's hourly limit. Try again later.");
			}
			window.addLast(now);
		}
	}

	/** Starter questions that fit the farm's situation today. */
	private List<String> suggestions(Farm farm) {
		List<String> out = new ArrayList<>();
		irrigation.evaluate(farm)
			.stream()
			.filter((state) -> state.crop() != null)
			.filter((state) -> switch (state.recommendation().status()) {
				case IRRIGATE_NOW, STRESSED -> true;
				default -> false;
			})
			.findFirst()
			.ifPresent((state) -> out.add("Why does " + state.field().getName() + " need water?"));
		out.add("Which fields need irrigation this week?");
		out.add("Is any water use abnormal right now?");
		out.add("Which pump is most likely to fail, and what should I do?");
		out.add("How did this week's water use compare with last week?");
		out.add("Write a short weekly farm report.");
		return out.stream().limit(5).toList();
	}

	private String quote(String raw) {
		return json.writeValueAsString((raw != null) ? raw : "");
	}

}
