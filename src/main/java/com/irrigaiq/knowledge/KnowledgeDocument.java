package com.irrigaiq.knowledge;

import java.util.List;

/** A curated document, parsed from {@code src/main/resources/knowledge}. */
public record KnowledgeDocument(String slug, String title, List<Source> sources, String checksum, List<Section> sections) {

	/** A published work the document summarises. {@code url} may be absent. */
	public record Source(String title, String publisher, String url) {
	}

	/** One retrieval chunk: a heading and its text. */
	record Section(String heading, String content) {
	}

}
