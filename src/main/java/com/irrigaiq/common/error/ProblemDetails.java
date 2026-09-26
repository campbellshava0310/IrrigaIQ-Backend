package com.irrigaiq.common.error;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.irrigaiq.common.web.RequestIdFilter;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds problem responses with the members every IrrigaIQ error carries: {@code code},
 * {@code requestId} and {@code timestamp}.
 */
public final class ProblemDetails {

	public static final String CODE = "code";

	private ProblemDetails() {
	}

	public static ProblemDetail of(HttpStatusCode status, ErrorCode code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty(CODE, code.name());
		return enrich(problem, status);
	}

	/** Adds the standard members to a problem, including ones created by Spring MVC itself. */
	public static ProblemDetail enrich(ProblemDetail problem, HttpStatusCode status) {
		Map<String, Object> properties = problem.getProperties();
		if (properties == null || !properties.containsKey(CODE)) {
			problem.setProperty(CODE, ErrorCode.fromStatus(status).name());
		}
		problem.setProperty("requestId", RequestIdFilter.currentRequestId());
		problem.setProperty("timestamp", Instant.now().toString());
		return problem;
	}

	/** Flat JSON-ready view, for writers that bypass Spring MVC message conversion (security filters). */
	public static Map<String, Object> toMap(ProblemDetail problem) {
		Map<String, Object> body = new LinkedHashMap<>();
		if (problem.getType() != null) { // Spring 7 leaves type unset, meaning "about:blank"
			body.put("type", problem.getType().toString());
		}
		body.put("title", problem.getTitle());
		body.put("status", problem.getStatus());
		body.put("detail", problem.getDetail());
		if (problem.getInstance() != null) {
			body.put("instance", problem.getInstance().toString());
		}
		if (problem.getProperties() != null) {
			body.putAll(problem.getProperties());
		}
		return body;
	}

}
