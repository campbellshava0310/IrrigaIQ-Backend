package com.irrigaiq.assistant;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The language model behind the assistant, reduced to what the tool loop needs. Implementations:
 * {@link ClaudeChatModel} (Claude API) and {@link OllamaChatModel} (a local model); tests use a
 * scripted fake. Which one runs is set by {@code irrigaiq.assistant.provider}.
 */
public interface ChatModel {

	boolean configured();

	/** The model answering, as shown to users (e.g. "claude-opus-5", "llama3.1:8b"). */
	String modelName();

	/** Where it runs, as shown to users: "Claude API" or "on this server". */
	String hostedBy();

	/** Why the model cannot answer right now (not running, not downloaded), or empty when ready. */
	default Optional<String> problem() {
		return Optional.empty();
	}

	/** Tool results are cut to this length, so a smaller context window is not overrun. */
	default int maxToolResultChars() {
		return 60_000;
	}

	/** The model cannot be reached or is not ready; the message is safe to show to users. */
	final class UnavailableException extends RuntimeException {

		public UnavailableException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/** Starts a conversation; {@code history} alternates user and assistant turns and ends with the user. */
	Conversation start(List<SystemBlock> system, List<ChatMessage> history, List<ToolSpec> tools);

	/** @param cacheable whether this block (and everything before it) may be served from the prompt cache */
	record SystemBlock(String text, boolean cacheable) {
	}

	record ChatMessage(Role role, String content) {
	}

	enum Role {

		USER, ASSISTANT

	}

	/** @param inputSchema JSON Schema of the tool's input (an object schema) */
	record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
	}

	/** @param input parsed input, or {@code null} when the model's JSON could not be parsed */
	record ToolCall(String id, String name, Map<String, Object> input, String rawInput) {
	}

	record ToolResult(String toolUseId, String content, boolean isError) {
	}

	enum StopKind {

		END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER

	}

	record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

		static final Usage NONE = new Usage(0, 0, 0, 0);

		Usage plus(Usage other) {
			return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
					cacheReadTokens + other.cacheReadTokens, cacheWriteTokens + other.cacheWriteTokens);
		}

	}

	/**
	 * One model response.
	 * @param toolCalls tool uses to run (never populated for a refusal)
	 * @param model the model that produced it (differs from the requested one after a fallback)
	 */
	record Turn(StopKind stop, String text, List<ToolCall> toolCalls, Usage usage, String model) {
	}

	/** Streaming callbacks for one turn. */
	interface TurnListener {

		void onText(String delta);

		/** The model began thinking (content is not shown to users). */
		default void onThinking() {
		}

		/** The model started writing a tool call; its input follows. */
		default void onToolCallStarted(String id, String name) {
		}

	}

	/** A conversation in progress: one {@link #next} per model turn, tool results in between. */
	interface Conversation extends AutoCloseable {

		Turn next(TurnListener listener);

		void addToolResults(List<ToolResult> results);

		/** Abandons the turn in progress (the client went away). */
		@Override
		void close();

	}

}
