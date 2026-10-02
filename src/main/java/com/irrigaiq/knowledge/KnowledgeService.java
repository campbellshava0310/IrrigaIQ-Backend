package com.irrigaiq.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.irrigaiq.knowledge.KnowledgeDocument.Source;
import com.irrigaiq.knowledge.KnowledgeStore.Hit;
import com.irrigaiq.ml.MlApi.EmbeddingResponse;
import com.irrigaiq.ml.MlClient;

import org.springframework.stereotype.Service;

/**
 * Retrieval over the curated knowledge base. Hybrid: semantic (embedding) search catches questions
 * phrased differently from the text; full-text search catches exact terms such as "ISO 10816" or
 * "blossom-end rot". The two rankings are merged with reciprocal-rank fusion. Without the ML
 * service, full-text search alone still answers.
 */
@Service
public class KnowledgeService {

	static final int CANDIDATES = 12;

	/** Reciprocal-rank-fusion constant (the usual value from the original RRF paper). */
	static final int RRF_K = 60;

	/** Semantic hits below this cosine similarity are unrelated to the question. */
	static final double MIN_SIMILARITY = 0.55;

	private final KnowledgeStore store;

	private final MlClient ml;

	public KnowledgeService(KnowledgeStore store, MlClient ml) {
		this.store = store;
		this.ml = ml;
	}

	/**
	 * @param method {@code HYBRID} (semantic + full text) or {@code FULL_TEXT} (ML service unavailable)
	 */
	public record SearchResult(String query, String method, List<Passage> passages) {
	}

	public record Passage(String documentSlug, String documentTitle, String heading, String content,
			List<Source> sources, double relevance) {
	}

	public SearchResult search(String query, int limit) {
		String q = query.strip();
		int k = Math.max(1, Math.min(limit, 8));
		List<Hit> semantic = List.of();
		EmbeddingResponse embedding = ml.embed(List.of(q), true).orElse(null);
		if (embedding != null && !embedding.vectors().isEmpty()) {
			semantic = store.vectorSearch(embedding.vectors().get(0), CANDIDATES)
				.stream()
				.filter((hit) -> hit.score() >= MIN_SIMILARITY)
				.toList();
		}
		List<Hit> text = store.textSearch(q, CANDIDATES);
		if (text.isEmpty()) {
			text = store.anyWordSearch(q, CANDIDATES);
		}

		Map<Long, Double> fused = new LinkedHashMap<>();
		Map<Long, Hit> hits = new LinkedHashMap<>();
		rank(semantic, fused, hits);
		rank(text, fused, hits);
		List<Passage> passages = new ArrayList<>();
		fused.entrySet()
			.stream()
			.sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
			.limit(k)
			.forEach((entry) -> {
				Hit hit = hits.get(entry.getKey());
				passages.add(new Passage(hit.slug(), hit.title(), hit.heading(), hit.content(), hit.sources(),
						Math.round(entry.getValue() * RRF_K * 500) / 1000.0));
			});
		return new SearchResult(q, (embedding != null) ? "HYBRID" : "FULL_TEXT", passages);
	}

	public record Status(int documents, int chunks, int embedded) {
	}

	public Status status() {
		KnowledgeStore.Stats stats = store.stats();
		return new Status(stats.documents(), stats.chunks(), stats.embedded());
	}

	private static void rank(List<Hit> ranked, Map<Long, Double> fused, Map<Long, Hit> hits) {
		for (int i = 0; i < ranked.size(); i++) {
			Hit hit = ranked.get(i);
			fused.merge(hit.chunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
			hits.putIfAbsent(hit.chunkId(), hit);
		}
	}

}
