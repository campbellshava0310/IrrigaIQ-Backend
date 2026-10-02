package com.irrigaiq.assistant;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageDeltaUsage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawContentBlockDelta;
import com.anthropic.models.beta.messages.BetaRawContentBlockStartEvent;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaRedactedThinkingBlockParam;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlockParam;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.MessageCreateParams;

import jakarta.annotation.PreDestroy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link ChatModel} on the Claude Messages API (beta surface, for server-side refusal fallbacks).
 *
 * <ul>
 * <li>Streams every turn, so text reaches the user as it is written.</li>
 * <li>Adaptive thinking with a configurable effort; thinking blocks are passed back unchanged on
 * the next turn, as the API requires during tool use.</li>
 * <li>Refusal fallbacks are on ({@code fallbacks: "default"}): a request declined by the safety
 * classifiers is re-run server-side on Anthropic's recommended fallback model instead of failing.</li>
 * <li>Tool inputs stream eagerly and are parsed strictly here; the caller validates them against the
 * tool's schema before running anything.</li>
 * <li>The tool definitions and static system prompt are cached (prompt caching), so each follow-up
 * turn re-reads them at a fraction of the cost.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "irrigaiq.assistant", name = "provider", havingValue = "CLAUDE", matchIfMissing = true)
class ClaudeChatModel implements ChatModel {

	/** Header for the {@code "default"} scalar form of {@code fallbacks}. */
	static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

	private final AssistantProperties properties;

	private final ObjectMapper json;

	private final AnthropicClient client;

	ClaudeChatModel(AssistantProperties properties, ObjectMapper json) {
		this.properties = properties;
		this.json = json;
		this.client = properties.configured() ? client(properties) : null;
	}

	private static AnthropicClient client(AssistantProperties properties) {
		AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
			.apiKey(properties.apiKey())
			.maxRetries(2)
			.timeout(properties.timeout());
		if (!properties.baseUrl().isBlank()) {
			builder.baseUrl(properties.baseUrl());
		}
		return builder.build();
	}

	@PreDestroy
	void shutdown() {
		if (client != null) {
			client.close();
		}
	}

	@Override
	public boolean configured() {
		return client != null;
	}

	@Override
	public String modelName() {
		return properties.model();
	}

	@Override
	public String hostedBy() {
		return "Claude API";
	}

	@Override
	public Conversation start(List<SystemBlock> system, List<ChatMessage> history, List<ToolSpec> tools) {
		if (client == null) {
			throw new IllegalStateException("The assistant has no API key");
		}
		List<BetaTextBlockParam> systemBlocks = system.stream().map((block) -> {
			BetaTextBlockParam.Builder builder = BetaTextBlockParam.builder().text(block.text());
			if (block.cacheable()) {
				builder.cacheControl(BetaCacheControlEphemeral.builder().build());
			}
			return builder.build();
		}).toList();
		List<BetaMessageParam> messages = new ArrayList<>();
		for (ChatMessage message : history) {
			messages.add(BetaMessageParam.builder()
				.role((message.role() == Role.USER) ? BetaMessageParam.Role.USER : BetaMessageParam.Role.ASSISTANT)
				.content(message.content())
				.build());
		}
		return new ClaudeConversation(systemBlocks, messages, tools.stream().map(ClaudeChatModel::tool).toList());
	}

	private static BetaTool tool(ToolSpec spec) {
		BetaTool.InputSchema.Properties.Builder properties = BetaTool.InputSchema.Properties.builder();
		@SuppressWarnings("unchecked")
		Map<String, Object> declared = (Map<String, Object>) spec.inputSchema().getOrDefault("properties", Map.of());
		declared.forEach((name, schema) -> properties.putAdditionalProperty(name, JsonValue.from(schema)));
		BetaTool.InputSchema.Builder schema = BetaTool.InputSchema.builder().properties(properties.build());
		@SuppressWarnings("unchecked")
		List<String> required = (List<String>) spec.inputSchema().getOrDefault("required", List.of());
		if (!required.isEmpty()) {
			schema.required(required);
		}
		if (spec.inputSchema().containsKey("additionalProperties")) {
			schema.putAdditionalProperty("additionalProperties",
					JsonValue.from(spec.inputSchema().get("additionalProperties")));
		}
		return BetaTool.builder()
			.name(spec.name())
			.description(spec.description())
			.inputSchema(schema.build())
			.eagerInputStreaming(true)
			.build();
	}

	/** One block of the assistant's reply, assembled from stream events. */
	private static final class Block {

		final String type;

		final StringBuilder text = new StringBuilder();

		final StringBuilder signature = new StringBuilder();

		String id;

		String name;

		String data;

		Block(String type) {
			this.type = type;
		}

	}

	private final class ClaudeConversation implements Conversation {

		private final List<BetaTextBlockParam> system;

		private final List<BetaMessageParam> messages;

		private final List<BetaTool> tools;

		private volatile boolean closed;

		private volatile StreamResponse<BetaRawMessageStreamEvent> current;

		ClaudeConversation(List<BetaTextBlockParam> system, List<BetaMessageParam> messages, List<BetaTool> tools) {
			this.system = system;
			this.messages = messages;
			this.tools = tools;
		}

		@Override
		public Turn next(TurnListener listener) {
			MessageCreateParams.Builder params = MessageCreateParams.builder()
				.model(properties.model())
				.maxTokens(properties.maxTokens())
				.systemOfBetaTextBlockParams(system)
				.messages(messages)
				.thinking(BetaThinkingConfigAdaptive.builder().build())
				.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(properties.effort())).build())
				.addBeta(FALLBACK_BETA)
				.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
			tools.forEach(params::addTool);

			Map<Long, Block> blocks = new LinkedHashMap<>();
			BetaStopReason stopReason = null;
			String model = properties.model();
			long input = 0;
			long output = 0;
			long cacheRead = 0;
			long cacheWrite = 0;
			try (StreamResponse<BetaRawMessageStreamEvent> stream = client.beta().messages().createStreaming(params.build())) {
				current = stream;
				Iterator<BetaRawMessageStreamEvent> events = stream.stream().iterator();
				while (events.hasNext()) {
					if (closed) {
						throw new CancellationException("Conversation closed");
					}
					BetaRawMessageStreamEvent event = events.next();
					if (event.isMessageStart()) {
						BetaMessage started = event.asMessageStart().message();
						model = started.model().asString();
						BetaUsage usage = started.usage();
						input = usage.inputTokens();
						cacheRead = usage.cacheReadInputTokens().orElse(0L);
						cacheWrite = usage.cacheCreationInputTokens().orElse(0L);
					}
					else if (event.isContentBlockStart()) {
						BetaRawContentBlockStartEvent start = event.asContentBlockStart();
						Block block = open(start.contentBlock());
						blocks.put(start.index(), block);
						if ("thinking".equals(block.type)) {
							listener.onThinking();
						}
						else if ("tool_use".equals(block.type)) {
							listener.onToolCallStarted(block.id, block.name);
						}
					}
					else if (event.isContentBlockDelta()) {
						Block block = blocks.get(event.asContentBlockDelta().index());
						BetaRawContentBlockDelta delta = event.asContentBlockDelta().delta();
						if (block == null) {
							continue;
						}
						if (delta.isText()) {
							block.text.append(delta.asText().text());
							listener.onText(delta.asText().text());
						}
						else if (delta.isThinking()) {
							block.text.append(delta.asThinking().thinking());
						}
						else if (delta.isSignature()) {
							block.signature.append(delta.asSignature().signature());
						}
						else if (delta.isInputJson()) {
							block.text.append(delta.asInputJson().partialJson());
						}
					}
					else if (event.isMessageDelta()) {
						stopReason = event.asMessageDelta().delta().stopReason().orElse(stopReason);
						BetaMessageDeltaUsage usage = event.asMessageDelta().usage();
						output = usage.outputTokens();
						input = usage.inputTokens().orElse(input);
					}
				}
			}
			finally {
				current = null;
			}
			if (closed) {
				throw new CancellationException("Conversation closed");
			}
			return finish(new ArrayList<>(blocks.values()), stopReason, model,
					new Usage(input, output, cacheRead, cacheWrite));
		}

		private Turn finish(List<Block> blocks, BetaStopReason stopReason, String model, Usage usage) {
			// After a mid-output fallback, blocks before the last fallback marker are the declined model's
			// work: only its text is kept; its thinking and tool calls are neither echoed nor run.
			int boundary = -1;
			for (int i = 0; i < blocks.size(); i++) {
				if ("fallback".equals(blocks.get(i).type)) {
					boundary = i;
				}
			}
			List<BetaContentBlockParam> echo = new ArrayList<>();
			List<ToolCall> calls = new ArrayList<>();
			StringBuilder text = new StringBuilder();
			for (int i = 0; i < blocks.size(); i++) {
				Block block = blocks.get(i);
				boolean current = i > boundary;
				switch (block.type) {
					case "text" -> {
						if (!block.text.isEmpty()) {
							echo.add(BetaContentBlockParam
								.ofText(BetaTextBlockParam.builder().text(block.text.toString()).build()));
							text.append(block.text);
						}
					}
					case "thinking" -> {
						if (current) {
							echo.add(BetaContentBlockParam.ofThinking(BetaThinkingBlockParam.builder()
								.thinking(block.text.toString())
								.signature(block.signature.toString())
								.build()));
						}
					}
					case "redacted_thinking" -> {
						if (current) {
							echo.add(BetaContentBlockParam
								.ofRedactedThinking(BetaRedactedThinkingBlockParam.builder().data(block.data).build()));
						}
					}
					case "tool_use" -> {
						if (current) {
							Map<String, Object> input = parse(block.text.toString());
							BetaToolUseBlockParam.Input.Builder echoed = BetaToolUseBlockParam.Input.builder();
							if (input != null) {
								input.forEach((key, value) -> echoed.putAdditionalProperty(key, JsonValue.from(value)));
							}
							echo.add(BetaContentBlockParam.ofToolUse(
									BetaToolUseBlockParam.builder().id(block.id).name(block.name).input(echoed.build()).build()));
							calls.add(new ToolCall(block.id, block.name, input, block.text.toString()));
						}
					}
					default -> {
						// fallback markers and unknown block types are not echoed
					}
				}
			}
			if (!echo.isEmpty()) {
				messages.add(BetaMessageParam.builder()
					.role(BetaMessageParam.Role.ASSISTANT)
					.contentOfBetaContentBlockParams(echo)
					.build());
			}
			StopKind stop = (stopReason == null) ? StopKind.OTHER
					: BetaStopReason.END_TURN.equals(stopReason) ? StopKind.END_TURN
							: BetaStopReason.TOOL_USE.equals(stopReason) ? StopKind.TOOL_USE
									: BetaStopReason.MAX_TOKENS.equals(stopReason) ? StopKind.MAX_TOKENS
											: BetaStopReason.REFUSAL.equals(stopReason) ? StopKind.REFUSAL
													: StopKind.OTHER;
			// A refusal can cut a tool call off mid-input: never hand such calls to the caller.
			return new Turn(stop, text.toString(), (stop == StopKind.REFUSAL) ? List.of() : List.copyOf(calls), usage,
					model);
		}

		@Override
		public void addToolResults(List<ToolResult> results) {
			// All results of one turn go back in a single user message.
			messages.add(BetaMessageParam.builder()
				.role(BetaMessageParam.Role.USER)
				.contentOfBetaContentBlockParams(results.stream()
					.map((result) -> BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
						.toolUseId(result.toolUseId())
						.content(result.content())
						.isError(result.isError())
						.build()))
					.toList())
				.build());
		}

		@Override
		public void close() {
			closed = true;
			StreamResponse<BetaRawMessageStreamEvent> stream = current;
			if (stream != null) {
				stream.close();
			}
		}

	}

	private static Block open(BetaRawContentBlockStartEvent.ContentBlock content) {
		if (content.isText()) {
			Block block = new Block("text");
			block.text.append(content.asText().text());
			return block;
		}
		if (content.isThinking()) {
			Block block = new Block("thinking");
			block.text.append(content.asThinking().thinking());
			block.signature.append(content.asThinking().signature());
			return block;
		}
		if (content.isRedactedThinking()) {
			Block block = new Block("redacted_thinking");
			block.data = content.asRedactedThinking().data();
			return block;
		}
		if (content.isToolUse()) {
			Block block = new Block("tool_use");
			block.id = content.asToolUse().id();
			block.name = content.asToolUse().name();
			return block;
		}
		// Block types this SDK version does not model (e.g. the "fallback" marker) arrive as raw JSON.
		Object type = content._json().map((raw) -> raw.convert(Map.class)).map((object) -> object.get("type")).orElse(null);
		return new Block((type instanceof String name) ? name : "unknown");
	}

	/** Strict parse: malformed or truncated JSON yields null, and the call is answered with an error. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> parse(String raw) {
		if (raw.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			Object value = json.readValue(raw, Object.class);
			return (value instanceof Map<?, ?> map) ? (Map<String, Object>) map : null;
		}
		catch (JacksonException ex) {
			return null;
		}
	}

}
