package com.irrigaiq.assistant;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.irrigaiq.assistant.ChatModel.ChatMessage;
import com.irrigaiq.assistant.ChatModel.Conversation;
import com.irrigaiq.assistant.ChatModel.Role;
import com.irrigaiq.assistant.ChatModel.StopKind;
import com.irrigaiq.assistant.ChatModel.SystemBlock;
import com.irrigaiq.assistant.ChatModel.ToolResult;
import com.irrigaiq.assistant.ChatModel.ToolSpec;
import com.irrigaiq.assistant.ChatModel.Turn;
import com.irrigaiq.assistant.ChatModel.TurnListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Ollama adapter against a fake Ollama server speaking its streamed chat protocol. */
class OllamaChatModelTest {

	private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("getFieldStatus", "One field",
			Map.of("type", "object", "properties", Map.of("field", Map.of("type", "string")))));

	private HttpServer server;

	private final List<String> responses = new ArrayList<>();

	private final List<String> requests = new CopyOnWriteArrayList<>();

	private int status = 200;

	private String tags = "{\"models\":[{\"name\":\"llama3.1:8b\"}]}";

	private final List<String> texts = new ArrayList<>();

	private final List<String> toolStarts = new ArrayList<>();

	private final TurnListener listener = new TurnListener() {
		@Override
		public void onText(String delta) {
			texts.add(delta);
		}

		@Override
		public void onToolCallStarted(String id, String name) {
			toolStarts.add(name);
		}
	};

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/chat", (exchange) -> {
			requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = responses.remove(0).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.createContext("/api/tags", (exchange) -> {
			byte[] body = tags.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private OllamaChatModel model(String baseUrl) {
		return new OllamaChatModel(new AssistantProperties(AssistantProperties.Provider.OLLAMA, "", "", "claude-opus-5",
				"medium", 32000, 8, 40, Duration.ofSeconds(10),
				new AssistantProperties.Ollama(baseUrl, "llama3.1:8b", 8192, 0.2, 12000)), JsonMapper.builder().build(),
				Clock.systemUTC());
	}

	private Conversation conversation() {
		return model("http://127.0.0.1:" + server.getAddress().getPort()).start(
				List.of(new SystemBlock("Rules", true), new SystemBlock("Farm", false)),
				List.of(new ChatMessage(Role.USER, "Why does Field A need water?")), TOOLS);
	}

	private static String lines(String... chunks) {
		return String.join("\n", chunks) + "\n";
	}

	@Test
	void streamsTextAndReportsUsage() {
		responses.add(lines("{\"message\":{\"role\":\"assistant\",\"content\":\"Block A \"},\"done\":false}",
				"{\"message\":{\"role\":\"assistant\",\"content\":\"needs water.\"},\"done\":false}",
				"{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\","
						+ "\"prompt_eval_count\":900,\"eval_count\":12}"));
		Turn turn = conversation().next(listener);
		assertThat(turn.stop()).isEqualTo(StopKind.END_TURN);
		assertThat(turn.text()).isEqualTo("Block A needs water.");
		assertThat(String.join("", texts)).isEqualTo("Block A needs water.");
		assertThat(turn.usage().inputTokens()).isEqualTo(900);
		assertThat(requests.get(0)).contains("\"tools\"", "\"num_ctx\":8192", "Rules", "Farm", "# Using tools");
	}

	@Test
	void runsNativeToolCallsAndSendsTheResultsBack() {
		responses.add(lines("{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":"
				+ "{\"name\":\"getFieldStatus\",\"arguments\":{\"field\":\"Field A\"}}}]},\"done\":false}",
				"{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}"));
		responses.add(lines("{\"message\":{\"role\":\"assistant\",\"content\":\"68% depleted.\"},\"done\":false}",
				"{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}"));
		Conversation conversation = conversation();
		Turn first = conversation.next(listener);
		assertThat(first.stop()).isEqualTo(StopKind.TOOL_USE);
		assertThat(first.toolCalls()).singleElement().satisfies((call) -> {
			assertThat(call.name()).isEqualTo("getFieldStatus");
			assertThat(call.input()).containsEntry("field", "Field A");
		});
		assertThat(toolStarts).containsExactly("getFieldStatus");

		conversation.addToolResults(List.of(new ToolResult(first.toolCalls().get(0).id(), "{\"depletionPct\":68}", false)));
		Turn second = conversation.next(listener);
		assertThat(second.text()).isEqualTo("68% depleted.");
		assertThat(requests.get(1)).contains("\"role\":\"tool\"", "\"tool_name\":\"getFieldStatus\"",
				"depletionPct", "\"tool_calls\"");
	}

	@Test
	void aToolCallWrittenAsTextIsRecoveredAndNotShown() {
		responses.add(lines("{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"name\\\": \\\"getFieldStatus\\\", \"},"
				+ "\"done\":false}", "{\"message\":{\"role\":\"assistant\",\"content\":\"\\\"parameters\\\": "
						+ "{\\\"field\\\": \\\"Block A\\\"}}\"},\"done\":false}",
				"{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}"));
		Turn turn = conversation().next(listener);
		assertThat(turn.stop()).isEqualTo(StopKind.TOOL_USE);
		assertThat(turn.toolCalls()).singleElement()
			.satisfies((call) -> assertThat(call.input()).containsEntry("field", "Block A"));
		assertThat(texts).isEmpty();
	}

	@Test
	void jsonThatIsNotAToolCallIsShownAsText() {
		responses.add(lines("{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"note\\\": 1}\"},\"done\":false}",
				"{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}"));
		Turn turn = conversation().next(listener);
		assertThat(turn.stop()).isEqualTo(StopKind.END_TURN);
		assertThat(texts).containsExactly("{\"note\": 1}");
	}

	@Test
	void aMissingModelExplainsHowToPullIt() {
		status = 404;
		responses.add("{\"error\":\"model 'llama3.1:8b' not found\"}");
		assertThatThrownBy(() -> conversation().next(listener)).isInstanceOf(ChatModel.UnavailableException.class)
			.hasMessageContaining("ollama pull llama3.1:8b");
	}

	@Test
	void readinessReportsAStoppedServerOrAMissingModel() {
		OllamaChatModel running = model("http://127.0.0.1:" + server.getAddress().getPort());
		assertThat(running.problem()).isEmpty();

		tags = "{\"models\":[{\"name\":\"llama3.2:3b\"}]}";
		OllamaChatModel withoutModel = model("http://127.0.0.1:" + server.getAddress().getPort());
		assertThat(withoutModel.problem()).hasValueSatisfying((p) -> assertThat(p).contains("not downloaded"));

		OllamaChatModel stopped = model("http://127.0.0.1:9");
		assertThat(stopped.problem()).hasValueSatisfying((p) -> assertThat(p).contains("not running"));
		assertThatThrownBy(() -> stopped.start(List.of(), List.of(new ChatMessage(Role.USER, "Hi")), TOOLS).next(listener))
			.isInstanceOf(ChatModel.UnavailableException.class);
	}

}
