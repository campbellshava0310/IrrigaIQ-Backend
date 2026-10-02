package com.irrigaiq.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.irrigaiq.assistant.AssistantService.Prepared;
import com.irrigaiq.assistant.ChatModel.ChatMessage;
import com.irrigaiq.assistant.ChatModel.Role;
import com.irrigaiq.assistant.ChatModel.StopKind;
import com.irrigaiq.assistant.ChatModel.ToolCall;
import com.irrigaiq.assistant.ChatModel.ToolSpec;
import com.irrigaiq.assistant.ChatModel.Turn;
import com.irrigaiq.assistant.ChatModel.Usage;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.irrigation.IrrigationService;
import com.irrigaiq.security.AuthenticatedUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantServiceTest {

	private static final ToolSpec FIELD_STATUS = new ToolSpec("getFieldStatus", "One field",
			Map.of("type", "object", "properties", Map.of("field", Map.of("type", "string", "minLength", 1)), "required",
					List.of("field"), "additionalProperties", false));

	private final ScriptedChatModel model = new ScriptedChatModel();

	private final FarmTools tools = mock(FarmTools.class);

	private final IrrigationService irrigation = mock(IrrigationService.class);

	private final List<String[]> events = new ArrayList<>();

	private AssistantService service;

	private Prepared request;

	@BeforeEach
	void setUp() {
		Farm farm = mock(Farm.class);
		when(farm.getId()).thenReturn(UUID.randomUUID());
		when(farm.getName()).thenReturn("Test Farm");
		when(farm.zone()).thenReturn(ZoneId.of("Africa/Harare"));
		when(farm.getTimezone()).thenReturn("Africa/Harare");
		when(farm.getCurrency()).thenReturn("USD");
		when(tools.specs()).thenReturn(List.of(FIELD_STATUS));
		when(irrigation.status(any(), any())).thenReturn(List.of());
		AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "grower@example.com", "Grower",
				Set.of(com.irrigaiq.security.Role.FARM_MANAGER), UUID.randomUUID());
		AssistantProperties properties = new AssistantProperties(AssistantProperties.Provider.CLAUDE, "test-key",
				"", "claude-opus-5", "medium", 32000, 3, 40, Duration.ofMinutes(5),
				new AssistantProperties.Ollama("http://localhost:11434", "llama3.1:8b", 12288, 0.2, 12000));
		service = new AssistantService(model, tools, mock(FarmService.class), irrigation, properties,
				mock(AuditService.class), JsonMapper.builder().build(),
				Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC));
		request = new Prepared(farm, user, List.of(new ChatMessage(Role.USER, "Why does Field A need water?")));
	}

	private void run() {
		service.run(request, (event, data) -> events.add(new String[] { event, String.valueOf(data) }));
	}

	private List<String> names() {
		return events.stream().map((e) -> e[0]).toList();
	}

	@Test
	void runsRequestedToolsAndStreamsTheAnswer() {
		when(tools.run(eq("getFieldStatus"), anyMap(), any())).thenReturn(Map.of("depletionPct", 68.0));
		model.then((l) -> ScriptedChatModel.tools(l, new ToolCall("t1", "getFieldStatus", Map.of("field", "Field A"), "")))
			.then((l) -> ScriptedChatModel.text(l, "Block A is 68 % depleted."));
		run();
		assertThat(names()).containsExactly("start", "tool", "tool", "text", "done");
		assertThat(events.get(2)[1]).contains("state=done");
		assertThat(model.toolResults).hasSize(1);
		assertThat(model.toolResults.get(0).get(0).content()).contains("68.0");
		assertThat(model.toolResults.get(0).get(0).isError()).isFalse();
		assertThat(model.system.get(0).cacheable()).isTrue();
		assertThat(model.system.get(1).text()).contains("Test Farm");
		assertThat(model.closed).isTrue();
	}

	@Test
	void invalidToolInputIsReturnedAsAnErrorAndNeverRuns() {
		model.then((l) -> ScriptedChatModel.tools(l, new ToolCall("t1", "getFieldStatus", Map.of(), "{}"),
				new ToolCall("t2", "getFieldStatus", null, "{\"field\": \"Blo")))
			.then((l) -> ScriptedChatModel.text(l, "Which field?"));
		run();
		verify(tools, never()).run(any(), any(), any());
		assertThat(model.toolResults.get(0)).allSatisfy((r) -> assertThat(r.isError()).isTrue());
		assertThat(model.toolResults.get(0).get(1).content()).contains("INVALID_JSON");
	}

	@Test
	void aRefusalStopsWithoutRunningTools() {
		model.then((l) -> new Turn(StopKind.REFUSAL, "", List.of(), Usage.NONE, "claude-opus-5"));
		run();
		assertThat(names()).containsExactly("start", "error", "done");
		assertThat(events.get(1)[1]).contains("REFUSED");
	}

	@Test
	void aTruncatedTurnDoesNotRunItsToolCall() {
		model.then((l) -> new Turn(StopKind.MAX_TOKENS, "", List.of(new ToolCall("t1", "getFieldStatus",
				Map.of("field", "Fie"), "{\"field\":\"Fie")), Usage.NONE, "claude-opus-5"));
		run();
		verify(tools, never()).run(any(), any(), any());
		assertThat(names()).contains("notice");
	}

	@Test
	void toolFailuresBecomeErrorResultsTheModelCanExplain() {
		when(tools.run(eq("getFieldStatus"), anyMap(), any()))
			.thenThrow(new FarmTools.ToolException("No field matches 'Z'"));
		model.then((l) -> ScriptedChatModel.tools(l, new ToolCall("t1", "getFieldStatus", Map.of("field", "Z"), "")))
			.then((l) -> ScriptedChatModel.text(l, "There is no field Z."));
		run();
		assertThat(model.toolResults.get(0).get(0).isError()).isTrue();
		assertThat(model.toolResults.get(0).get(0).content()).contains("No field matches");
		assertThat(names()).endsWith("done");
	}

	@Test
	void placeholderValuesInOptionalParametersMeanNoFilter() {
		ToolSpec risk = new ToolSpec("getEquipmentRisk", "Pumps", Map.of("type", "object", "properties",
				Map.of("asset", Map.of("type", "string", "minLength", 1)), "required", List.of()));
		assertThat(AssistantService.withoutPlaceholders(risk, Map.of("asset", "all"))).isEmpty();
		assertThat(AssistantService.withoutPlaceholders(risk, Map.of("asset", " "))).isEmpty();
		assertThat(AssistantService.withoutPlaceholders(risk, Map.of("asset", "P-2"))).containsEntry("asset", "P-2");
		// Required parameters are never dropped: an empty one must fail validation instead.
		assertThat(AssistantService.withoutPlaceholders(FIELD_STATUS, Map.of("field", ""))).containsKey("field");
	}

	@Test
	void theToolBudgetForcesAnAnswer() {
		when(tools.run(eq("getFieldStatus"), anyMap(), any())).thenReturn(Map.of());
		for (int i = 0; i < 3; i++) {
			String id = "t" + i;
			model.then((l) -> ScriptedChatModel.tools(l, new ToolCall(id, "getFieldStatus", Map.of("field", "A"), "")));
		}
		model.then((l) -> ScriptedChatModel.text(l, "Here is what I found."));
		run();
		// Rounds 1-2 run tools; round 3 (the budget) answers them with "not run" so the model must answer.
		assertThat(model.toolResults).hasSize(3);
		assertThat(model.toolResults.get(2).get(0).content()).contains("budget");
		assertThat(names()).endsWith("done");
	}

}
