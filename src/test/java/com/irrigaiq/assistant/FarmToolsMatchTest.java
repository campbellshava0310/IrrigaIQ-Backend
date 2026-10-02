package com.irrigaiq.assistant;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FarmToolsMatchTest {

	record Item(UUID id, String name) {
	}

	private final List<Item> fields = List.of(new Item(UUID.randomUUID(), "Block A · Tomatoes"),
			new Item(UUID.randomUUID(), "Block B · Tomatoes"), new Item(UUID.randomUUID(), "Pivot 1 · Maize"));

	private Item match(String query) {
		return FarmTools.match(query, fields, Item::name, Item::id, "field");
	}

	@Test
	void findsFieldsTheWayPeopleNameThem() {
		assertThat(match("Field A").name()).isEqualTo("Block A · Tomatoes");
		assertThat(match("block b").name()).isEqualTo("Block B · Tomatoes");
		assertThat(match("the maize pivot").name()).isEqualTo("Pivot 1 · Maize");
		assertThat(match(fields.get(2).id().toString()).name()).isEqualTo("Pivot 1 · Maize");
	}

	@Test
	void ambiguousOrUnknownNamesListTheOptions() {
		assertThatThrownBy(() -> match("tomatoes")).hasMessageContaining("matches several")
			.hasMessageContaining("Block A · Tomatoes");
		assertThatThrownBy(() -> match("Field Z")).hasMessageContaining("No field matches")
			.hasMessageContaining("Pivot 1 · Maize");
	}

}
