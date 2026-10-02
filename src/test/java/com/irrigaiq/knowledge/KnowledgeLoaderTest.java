package com.irrigaiq.knowledge;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeLoaderTest {

	@Test
	void parsesFrontMatterAndSplitsSections() {
		KnowledgeDocument document = KnowledgeLoader.parse("pumps", """
				---
				title: Pumps
				sources:
				  - title: A sourcebook
				    publisher: DOE
				    url: https://example.org
				---

				## Energy
				Energy per cubic metre
				rises with wear.

				## Vibration
				Measure it.
				""");
		assertThat(document.title()).isEqualTo("Pumps");
		assertThat(document.sources()).containsExactly(
				new KnowledgeDocument.Source("A sourcebook", "DOE", "https://example.org"));
		assertThat(document.sections()).extracting(KnowledgeDocument.Section::heading)
			.containsExactly("Energy", "Vibration");
		assertThat(document.sections().get(0).content()).isEqualTo("Energy per cubic metre rises with wear.");
		assertThat(document.checksum()).hasSize(64);
	}

	@Test
	void longSectionsAreSplitAtParagraphs() {
		String paragraph = "word ".repeat(200).strip();
		KnowledgeDocument document = KnowledgeLoader.parse("long", """
				---
				title: Long
				---
				## Big
				%s

				%s

				%s
				""".formatted(paragraph, paragraph, paragraph));
		assertThat(document.sections()).hasSizeGreaterThan(1)
			.allSatisfy((s) -> assertThat(s.content().length()).isLessThanOrEqualTo(KnowledgeLoader.MAX_CHUNK_CHARS));
	}

	@Test
	void rejectsDocumentsWithoutTitle() {
		assertThatThrownBy(() -> KnowledgeLoader.parse("x", "---\nsources: []\n---\n## A\ntext\n"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void everyShippedDocumentHasATitleSourcesAndSections() throws Exception {
		List<KnowledgeDocument> documents = new KnowledgeLoader().load();
		assertThat(documents).hasSizeGreaterThanOrEqualTo(10);
		assertThat(documents).extracting(KnowledgeDocument::slug).doesNotContain("README");
		assertThat(documents).allSatisfy((d) -> {
			assertThat(d.title()).isNotBlank();
			assertThat(d.sources()).isNotEmpty();
			assertThat(d.sections()).isNotEmpty();
		});
	}

}
