package com.irrigaiq.assistant.web;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.assistant.AssistantProperties;
import com.irrigaiq.assistant.AssistantService;
import com.irrigaiq.assistant.AssistantService.ClientGoneException;
import com.irrigaiq.assistant.AssistantService.Message;
import com.irrigaiq.assistant.AssistantService.Prepared;
import com.irrigaiq.assistant.AssistantService.Status;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The farm assistant. Answers stream as server-sent events: {@code start}, {@code status},
 * {@code text} (answer deltas), {@code tool} (data being gathered), {@code sources}, {@code notice},
 * {@code error} and finally {@code done}. The conversation lives in the client, which sends the
 * whole history with each question; the server keeps no chat state.
 */
@RestController
@RequestMapping("/api/v1/farms/{farmId}/assistant")
@Tag(name = "Assistant")
public class AssistantController {

	private final AssistantService assistant;

	private final AssistantProperties properties;

	private final TaskExecutor executor;

	public AssistantController(AssistantService assistant, AssistantProperties properties,
			@Qualifier("assistantExecutor") TaskExecutor executor) {
		this.assistant = assistant;
		this.properties = properties;
		this.executor = executor;
	}

	public record ChatRequest(@NotEmpty @Size(max = 40) List<@Valid Message> messages) {
	}

	@GetMapping
	@Operation(summary = "Whether the assistant is available, and starter questions for this farm")
	public Status status(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return assistant.status(farmId, user);
	}

	@PostMapping("/chat")
	@Operation(summary = "Ask the assistant; the answer streams back as server-sent events")
	public SseEmitter chat(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId,
			@Valid @RequestBody ChatRequest body) {
		Prepared prepared = assistant.prepare(farmId, user, body.messages());
		SseEmitter emitter = new SseEmitter(properties.timeout().plus(Duration.ofSeconds(30)).toMillis());
		emitter.onCompletion(prepared::cancel);
		emitter.onTimeout(prepared::cancel);
		emitter.onError((ex) -> prepared.cancel());
		try {
			executor.execute(() -> {
				try {
					assistant.run(prepared, (event, data) -> send(emitter, event, data));
					emitter.complete();
				}
				catch (ClientGoneException ex) {
					emitter.complete();
				}
			});
		}
		catch (RejectedExecutionException ex) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
					"The assistant is at capacity. Try again in a moment.");
		}
		return emitter;
	}

	private static void send(SseEmitter emitter, String event, Object data) {
		try {
			emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException ex) {
			throw new ClientGoneException(ex);
		}
	}

}
