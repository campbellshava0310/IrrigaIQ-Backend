package com.irrigaiq.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.irrigaiq.knowledge.KnowledgeDocument.Section;
import com.irrigaiq.knowledge.KnowledgeDocument.Source;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Documents, chunks and their embeddings (pgvector), plus full-text search as the fallback. */
@Repository
class KnowledgeStore {

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	KnowledgeStore(JdbcClient jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	record Chunk(long id, String documentTitle, String heading, String content) {
	}

	/** A search result; {@code score} is cosine similarity for vector hits, a rank score otherwise. */
	record Hit(long chunkId, String slug, String title, String heading, String content, List<Source> sources,
			double score) {
	}

	Optional<String> checksum(String slug) {
		return jdbc.sql("select checksum from knowledge_documents where slug = ?")
			.param(slug)
			.query(String.class)
			.optional();
	}

	/** Replaces a document and its chunks (embeddings are recomputed afterwards). */
	void replace(KnowledgeDocument document, Instant now) {
		UUID id = jdbc.sql("""
				insert into knowledge_documents (id, slug, title, sources, checksum, indexed_at)
				values (?, ?, ?, cast(? as jsonb), ?, ?)
				on conflict (slug) do update set title = excluded.title, sources = excluded.sources,
				    checksum = excluded.checksum, indexed_at = excluded.indexed_at
				returning id
				""")
			.params(UUID.randomUUID(), document.slug(), document.title(), json.writeValueAsString(document.sources()),
					document.checksum(), Timestamp.from(now))
			.query(UUID.class)
			.single();
		jdbc.sql("delete from knowledge_chunks where document_id = ?").param(id).update();
		int ordinal = 0;
		for (Section section : document.sections()) {
			jdbc.sql("insert into knowledge_chunks (document_id, ordinal, heading, content) values (?, ?, ?, ?)")
				.params(id, ordinal++, section.heading(), section.content())
				.update();
		}
	}

	int deleteOtherThan(Collection<String> slugs) {
		if (slugs.isEmpty()) {
			return jdbc.sql("delete from knowledge_documents").update();
		}
		return jdbc.sql("delete from knowledge_documents where slug not in (:slugs)").param("slugs", slugs).update();
	}

	List<Chunk> withoutEmbedding(int limit) {
		return jdbc.sql("""
				select c.id, d.title, c.heading, c.content from knowledge_chunks c
				join knowledge_documents d on d.id = c.document_id
				where c.embedding is null order by c.id limit ?
				""")
			.param(limit)
			.query((rs, row) -> new Chunk(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
			.list();
	}

	void setEmbedding(long chunkId, List<Double> vector, String model) {
		jdbc.sql("update knowledge_chunks set embedding = cast(? as vector), embedding_model = ? where id = ?")
			.params(literal(vector), model, chunkId)
			.update();
	}

	record Stats(int documents, int chunks, int embedded) {
	}

	Stats stats() {
		return jdbc.sql("""
				select (select count(*) from knowledge_documents), count(*), count(embedding) from knowledge_chunks
				""").query((rs, row) -> new Stats(rs.getInt(1), rs.getInt(2), rs.getInt(3))).single();
	}

	List<Hit> vectorSearch(List<Double> query, int limit) {
		return jdbc.sql("""
				select c.id, d.slug, d.title, c.heading, c.content, d.sources,
				       1 - (c.embedding <=> cast(:q as vector)) as score
				from knowledge_chunks c join knowledge_documents d on d.id = c.document_id
				where c.embedding is not null
				order by c.embedding <=> cast(:q as vector) limit :limit
				""").param("q", literal(query)).param("limit", limit).query((rs, row) -> hit(rs)).list();
	}

	List<Hit> textSearch(String query, int limit) {
		return jdbc.sql("""
				select c.id, d.slug, d.title, c.heading, c.content, d.sources,
				       ts_rank_cd(c.search, websearch_to_tsquery('english', :q)) as score
				from knowledge_chunks c join knowledge_documents d on d.id = c.document_id
				where c.search @@ websearch_to_tsquery('english', :q)
				order by score desc limit :limit
				""").param("q", query).param("limit", limit).query((rs, row) -> hit(rs)).list();
	}

	/**
	 * Any-word full-text match, for questions phrased in words the documents do not use together
	 * (plain {@code websearch_to_tsquery} requires every word). Passages matching more of the
	 * question's distinct words rank first, so a common word like "pump" cannot drown out a rare,
	 * telling one like "gravel".
	 */
	List<Hit> anyWordSearch(String query, int limit) {
		List<String> words = java.util.Arrays.stream(query.toLowerCase().split("[^\\p{L}\\p{N}]+"))
			.filter((word) -> word.length() > 2)
			.distinct()
			.limit(12)
			.toList();
		if (words.isEmpty()) {
			return List.of();
		}
		StringBuilder coverage = new StringBuilder();
		for (int i = 0; i < words.size(); i++) {
			coverage.append((i == 0) ? "" : " + ").append("(c.search @@ plainto_tsquery('english', :w").append(i)
				.append("))::int");
		}
		JdbcClient.StatementSpec statement = jdbc.sql("""
				select c.id, d.slug, d.title, c.heading, c.content, d.sources,
				       ts_rank_cd(c.search, to_tsquery('english', :any)) as score, %s as coverage
				from knowledge_chunks c join knowledge_documents d on d.id = c.document_id
				where c.search @@ to_tsquery('english', :any)
				order by coverage desc, score desc limit :limit
				""".formatted(coverage))
			.param("any", words.stream().map((w) -> "'" + w + "'").collect(Collectors.joining(" | ")))
			.param("limit", limit);
		for (int i = 0; i < words.size(); i++) {
			statement = statement.param("w" + i, words.get(i));
		}
		return statement.query((rs, row) -> hit(rs)).list();
	}

	private Hit hit(java.sql.ResultSet rs) throws java.sql.SQLException {
		List<Source> sources = json.readValue(rs.getString("sources"), new TypeReference<List<Source>>() {
		});
		return new Hit(rs.getLong("id"), rs.getString("slug"), rs.getString("title"), rs.getString("heading"),
				rs.getString("content"), sources, rs.getDouble("score"));
	}

	private static String literal(List<Double> vector) {
		return vector.stream().map((v) -> Float.toString(v.floatValue())).collect(Collectors.joining(",", "[", "]"));
	}

}
