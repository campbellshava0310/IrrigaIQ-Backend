package com.irrigaiq.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.irrigaiq.knowledge.KnowledgeDocument.Section;
import com.irrigaiq.knowledge.KnowledgeDocument.Source;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Reads the curated markdown documents: YAML front matter ({@code title}, {@code sources}) and
 * {@code ##} sections. Sections longer than {@link #MAX_CHUNK_CHARS} are split at paragraph breaks,
 * so every chunk stays well inside the embedding model's input limit.
 */
@Component
class KnowledgeLoader {

	static final String LOCATION = "classpath*:knowledge/*.md";

	static final int MAX_CHUNK_CHARS = 1600;

	List<KnowledgeDocument> load() throws IOException {
		Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
		List<KnowledgeDocument> documents = new ArrayList<>();
		for (Resource resource : Arrays.stream(resources)
			.sorted(Comparator.comparing(Resource::getFilename))
			.toList()) {
			String name = resource.getFilename();
			if (name == null || name.equalsIgnoreCase("README.md")) {
				continue;
			}
			try (InputStream in = resource.getInputStream()) {
				documents.add(parse(name.substring(0, name.length() - 3),
						new String(in.readAllBytes(), StandardCharsets.UTF_8)));
			}
		}
		return documents;
	}

	@SuppressWarnings("unchecked")
	static KnowledgeDocument parse(String slug, String text) {
		String normalised = text.replace("\r\n", "\n");
		if (!normalised.startsWith("---\n")) {
			throw new IllegalArgumentException("Knowledge document " + slug + " has no front matter");
		}
		int end = normalised.indexOf("\n---\n", 4);
		if (end < 0) {
			throw new IllegalArgumentException("Knowledge document " + slug + " has unterminated front matter");
		}
		Map<String, Object> meta = new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(normalised.substring(4, end));
		String title = (String) meta.get("title");
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("Knowledge document " + slug + " has no title");
		}
		List<Source> sources = new ArrayList<>();
		for (Map<String, Object> source : (List<Map<String, Object>>) meta.getOrDefault("sources", List.of())) {
			sources.add(new Source((String) source.get("title"), (String) source.get("publisher"),
					(String) source.get("url")));
		}
		String body = normalised.substring(end + 5);
		return new KnowledgeDocument(slug, title.strip(), List.copyOf(sources), sha256(normalised), sections(body));
	}

	private static List<Section> sections(String body) {
		List<Section> sections = new ArrayList<>();
		String heading = null;
		StringBuilder content = new StringBuilder();
		for (String line : body.split("\n", -1)) {
			if (line.startsWith("## ")) {
				flush(sections, heading, content);
				heading = line.substring(3).strip();
				content.setLength(0);
			}
			else {
				content.append(line).append('\n');
			}
		}
		flush(sections, heading, content);
		return sections;
	}

	/** Adds a section, split at paragraph breaks when it is too long for one chunk. */
	private static void flush(List<Section> sections, String heading, StringBuilder content) {
		String text = content.toString().strip();
		if (text.isEmpty()) {
			return;
		}
		String[] paragraphs = text.split("\n\\s*\n");
		StringBuilder chunk = new StringBuilder();
		for (String paragraph : paragraphs) {
			String flat = paragraph.strip().replaceAll("\\s*\n\\s*", " ");
			if (chunk.length() > 0 && chunk.length() + flat.length() > MAX_CHUNK_CHARS) {
				sections.add(new Section(heading, chunk.toString()));
				chunk.setLength(0);
			}
			if (chunk.length() > 0) {
				chunk.append("\n\n");
			}
			chunk.append(flat);
		}
		if (chunk.length() > 0) {
			sections.add(new Section(heading, chunk.toString()));
		}
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
