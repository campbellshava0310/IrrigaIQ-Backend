package com.irrigaiq.knowledge;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.irrigaiq.knowledge.KnowledgeService.Passage;
import com.irrigaiq.knowledge.KnowledgeService.SearchResult;
import com.irrigaiq.knowledge.KnowledgeStore.Chunk;
import com.irrigaiq.knowledge.KnowledgeStore.Hit;
import com.irrigaiq.support.IntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeIT extends IntegrationTest {

	@Autowired
	private KnowledgeIndexer indexer;

	@Autowired
	private KnowledgeStore store;

	@Autowired
	private KnowledgeService knowledge;

	@Test
	void documentsAreIndexedOnceAndFoundByFullTextWithoutTheMlService() throws Exception {
		indexer.syncDocuments();
		KnowledgeStore.Stats first = store.stats();
		assertThat(first.documents()).isGreaterThanOrEqualTo(10);
		assertThat(first.chunks()).isGreaterThan(first.documents());
		indexer.syncDocuments(); // unchanged documents are left alone
		assertThat(store.stats()).isEqualTo(first);

		SearchResult rot = knowledge.search("What causes blossom-end rot in tomatoes?", 3);
		assertThat(rot.method()).isEqualTo("FULL_TEXT");
		assertThat(rot.passages()).isNotEmpty();
		Passage top = rot.passages().get(0);
		assertThat(top.documentTitle()).isEqualTo("Crop water needs and sensitive growth stages");
		assertThat(top.heading()).isEqualTo("Tomato");
		assertThat(top.sources()).isNotEmpty();

		// Words the documents do not use all together still find the closest passage.
		assertThat(knowledge.search("my pump makes a noise like gravel", 3).passages())
			.extracting(Passage::heading)
			.contains("Cavitation");

		Tokens user = register(uniqueEmail("reader"));
		mvc.perform(bearer(get("/api/v1/knowledge/search?q=leak+at+night&limit=2"), user.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.passages[0].sources[0].title").exists());
	}

	@Test
	void vectorSearchRanksByCosineSimilarityInPgvector() throws Exception {
		indexer.syncDocuments();
		List<Chunk> chunks = store.withoutEmbedding(1000);
		assertThat(chunks).hasSizeGreaterThan(3);
		// One-hot vectors: each chunk points in its own direction, so a query equal to one chunk's vector
		// must return that chunk first with similarity 1.
		for (int i = 0; i < chunks.size(); i++) {
			store.setEmbedding(chunks.get(i).id(), oneHot(i % 384), "test");
		}
		Hit best = store.vectorSearch(oneHot(2), 3).get(0);
		assertThat(best.chunkId()).isEqualTo(chunks.get(2).id());
		assertThat(best.score()).isCloseTo(1.0, within(1e-6));
		assertThat(best.sources()).isNotEmpty();
	}

	private static List<Double> oneHot(int index) {
		List<Double> vector = new ArrayList<>(384);
		for (int i = 0; i < 384; i++) {
			vector.add((i == index) ? 1.0 : 0.0);
		}
		return vector;
	}

}
