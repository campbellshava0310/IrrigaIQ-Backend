package com.irrigaiq.assistant;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolInputValidatorTest {

	private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties",
			Map.of("field", Map.of("type", "string", "minLength", 1, "maxLength", 20), "days",
					Map.of("type", "integer", "minimum", 1, "maximum", 90), "mode",
					Map.of("type", "string", "enum", List.of("a", "b"))),
			"required", List.of("field"), "additionalProperties", false);

	@Test
	void acceptsValidInput() {
		assertThat(ToolInputValidator.validate(SCHEMA, Map.of("field", "Block A", "days", 14))).isEmpty();
		assertThat(ToolInputValidator.validate(SCHEMA, Map.of("field", "Block A", "days", 14.0))).isEmpty();
	}

	@Test
	void rejectsWhatAStreamedOrTruncatedInputCanLookLike() {
		assertThat(ToolInputValidator.validate(SCHEMA, null)).isNotEmpty();
		assertThat(ToolInputValidator.validate(SCHEMA, Map.of())).contains("'field' is required");
		assertThat(ToolInputValidator.validate(SCHEMA, Map.of("field", "A", "days", "14"))).isNotEmpty();
		assertThat(ToolInputValidator.validate(SCHEMA, Map.of("field", "A", "days", 14.5))).isNotEmpty();
		assertThat(ToolInputValidator.validate(SCHEMA, Map.of("field", "A", "days", 900))).isNotEmpty();
		assertThat(ToolInputValidator.validate(SCHEMA, Map.of("field", " "))).isNotEmpty();
		assertThat(ToolInputValidator.validate(SCHEMA, Map.of("field", "A", "mode", "c"))).isNotEmpty();
		assertThat(ToolInputValidator.validate(SCHEMA, Map.of("field", "A", "extra", 1)))
			.contains("unknown property 'extra'");
	}

}
