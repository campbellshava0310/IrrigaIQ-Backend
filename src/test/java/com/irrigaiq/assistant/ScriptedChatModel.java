package com.irrigaiq.assistant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/** A {@link ChatModel} that replays scripted turns and records what the loop sent back. */
class ScriptedChatModel implements ChatModel {

	final Deque<Function<TurnListener, Turn>> turns = new ArrayDeque<>();

	final List<List<ToolResult>> toolResults = new ArrayList<>();

	List<SystemBlock> system;

	List<ChatMessage> history;

	boolean closed;

	@Override
	public boolean configured() {
		return true;
	}

	@Override
	public String modelName() {
		return "scripted";
	}

	@Override
	public String hostedBy() {
		return "in tests";
	}

	ScriptedChatModel then(Function<TurnListener, Turn> turn) {
		turns.add(turn);
		return this;
	}

	static Turn text(TurnListener listener, String text) {
		listener.onText(text);
		return new Turn(StopKind.END_TURN, text, List.of(), new Usage(100, 20, 80, 0), "claude-opus-5");
	}

	static Turn tools(TurnListener listener, ToolCall... calls) {
		for (ToolCall call : calls) {
			listener.onToolCallStarted(call.id(), call.name());
		}
		return new Turn(StopKind.TOOL_USE, "", List.of(calls), new Usage(100, 20, 0, 0), "claude-opus-5");
	}

	@Override
	public Conversation start(List<SystemBlock> system, List<ChatMessage> history, List<ToolSpec> tools) {
		this.system = system;
		this.history = history;
		return new Conversation() {

			@Override
			public Turn next(TurnListener listener) {
				if (turns.isEmpty()) {
					throw new IllegalStateException("The loop asked for more turns than scripted");
				}
				return turns.poll().apply(listener);
			}

			@Override
			public void addToolResults(List<ToolResult> results) {
				toolResults.add(results);
			}

			@Override
			public void close() {
				closed = true;
			}

		};
	}

}
