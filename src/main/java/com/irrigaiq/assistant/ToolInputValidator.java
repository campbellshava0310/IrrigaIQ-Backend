package com.irrigaiq.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks a tool call's input against the tool's JSON schema before it runs. Tool inputs stream
 * eagerly (unbuffered), so a truncated or malformed input can reach us as a plausible-looking
 * object; it must never run. Supports the subset of JSON Schema the farm tools use: object with
 * typed properties (string, integer, number, boolean), enum, bounds, lengths, required and
 * {@code additionalProperties: false}.
 */
final class ToolInputValidator {

	private ToolInputValidator() {
	}

	/** @return problems found; empty when the input is valid */
	@SuppressWarnings("unchecked")
	static List<String> validate(Map<String, Object> schema, Map<String, Object> input) {
		List<String> problems = new ArrayList<>();
		if (input == null) {
			problems.add("input is not a JSON object");
			return problems;
		}
		Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
		for (String required : (List<String>) schema.getOrDefault("required", List.of())) {
			if (!input.containsKey(required) || input.get(required) == null) {
				problems.add("'" + required + "' is required");
			}
		}
		for (Map.Entry<String, Object> entry : input.entrySet()) {
			Map<String, Object> property = (Map<String, Object>) properties.get(entry.getKey());
			if (property == null) {
				if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
					problems.add("unknown property '" + entry.getKey() + "'");
				}
				continue;
			}
			check(entry.getKey(), property, entry.getValue(), problems);
		}
		return problems;
	}

	@SuppressWarnings("unchecked")
	private static void check(String name, Map<String, Object> property, Object value, List<String> problems) {
		if (value == null) {
			return;
		}
		String type = (String) property.get("type");
		boolean typeOk = switch (type) {
			case "string" -> value instanceof String;
			case "integer" -> value instanceof Integer || value instanceof Long
					|| (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()));
			case "number" -> value instanceof Number;
			case "boolean" -> value instanceof Boolean;
			default -> true;
		};
		if (!typeOk) {
			problems.add("'" + name + "' must be a " + type);
			return;
		}
		if (property.containsKey("enum") && !((List<Object>) property.get("enum")).contains(value)) {
			problems.add("'" + name + "' must be one of " + property.get("enum"));
		}
		if (value instanceof String text) {
			if (property.get("minLength") instanceof Number min && text.strip().length() < min.intValue()) {
				problems.add("'" + name + "' is too short");
			}
			if (property.get("maxLength") instanceof Number max && text.length() > max.intValue()) {
				problems.add("'" + name + "' is too long");
			}
		}
		if (value instanceof Number number) {
			if (property.get("minimum") instanceof Number min && number.doubleValue() < min.doubleValue()) {
				problems.add("'" + name + "' must be at least " + min);
			}
			if (property.get("maximum") instanceof Number max && number.doubleValue() > max.doubleValue()) {
				problems.add("'" + name + "' must be at most " + max);
			}
		}
	}

}
