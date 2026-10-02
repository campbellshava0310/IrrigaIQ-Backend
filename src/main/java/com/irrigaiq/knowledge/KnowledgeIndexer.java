package com.irrigaiq.knowledge;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.irrigaiq.knowledge.KnowledgeStore.Chunk;
import com.irrigaiq.ml.MlApi.EmbeddingResponse;
import com.irrigaiq.ml.MlClient;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the knowledge tables in step with the shipped documents. Text is indexed at startup, so
 * full-text search works immediately; embeddings are added as soon as the ML service is reachable,
 * and retried periodically until every chunk has one.
 */
@Component
class KnowledgeIndexer {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

	static final int EMBEDDING_BATCH = 32;

	private final KnowledgeLoader loader;

	private final KnowledgeStore store;

	private final MlClient ml;

	private final KnowledgeProperties properties;

	private final TaskScheduler scheduler;

	private final TransactionTemplate transactions;

	private final Clock clock;

	private final AtomicBoolean running = new AtomicBoolean();

	KnowledgeIndexer(KnowledgeLoader loader, KnowledgeStore store, MlClient ml, KnowledgeProperties properties,
			TaskScheduler scheduler, TransactionTemplate transactions, Clock clock) {
		this.loader = loader;
		this.store = store;
		this.ml = ml;
		this.properties = properties;
		this.scheduler = scheduler;
		this.transactions = transactions;
		this.clock = clock;
	}

	@EventListener(ApplicationReadyEvent.class)
	void onReady() {
		if (properties.indexOnStartup()) {
			scheduler.schedule(this::run, clock.instant());
		}
	}

	@Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT10M")
	void retryEmbeddings() {
		if (properties.indexOnStartup()) {
			run();
		}
	}

	/** Indexes changed documents, then embeds chunks that have no embedding yet. */
	void run() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		try {
			syncDocuments();
			embedPending();
		}
		catch (RuntimeException | IOException ex) {
			log.warn("Knowledge indexing failed; will retry", ex);
		}
		finally {
			running.set(false);
		}
	}

	void syncDocuments() throws IOException {
		List<KnowledgeDocument> documents = loader.load();
		int changed = 0;
		for (KnowledgeDocument document : documents) {
			if (!store.checksum(document.slug()).map(document.checksum()::equals).orElse(false)) {
				transactions.executeWithoutResult((status) -> store.replace(document, clock.instant()));
				changed++;
			}
		}
		int removed = transactions.execute(
				(status) -> store.deleteOtherThan(documents.stream().map(KnowledgeDocument::slug).toList()));
		if (changed > 0 || removed > 0) {
			log.info("Knowledge base: {} document(s) indexed, {} removed", changed, removed);
		}
	}

	void embedPending() {
		int embedded = 0;
		while (ml.available()) {
			List<Chunk> pending = store.withoutEmbedding(EMBEDDING_BATCH);
			if (pending.isEmpty()) {
				break;
			}
			EmbeddingResponse response = ml
				.embed(pending.stream().map(KnowledgeIndexer::embeddingText).toList(), false)
				.orElse(null);
			if (response == null || response.vectors().size() != pending.size()) {
				break;
			}
			for (int i = 0; i < pending.size(); i++) {
				store.setEmbedding(pending.get(i).id(), response.vectors().get(i), response.model());
			}
			embedded += pending.size();
		}
		if (embedded > 0) {
			log.info("Knowledge base: embedded {} chunk(s)", embedded);
		}
	}

	/** Title and heading give a short chunk the context it needs to be found. */
	static String embeddingText(Chunk chunk) {
		return chunk.documentTitle() + ((chunk.heading() != null) ? " — " + chunk.heading() : "") + "\n\n"
				+ chunk.content();
	}

}
