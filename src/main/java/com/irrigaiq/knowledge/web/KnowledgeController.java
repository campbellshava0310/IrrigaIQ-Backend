package com.irrigaiq.knowledge.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.knowledge.KnowledgeService;
import com.irrigaiq.knowledge.KnowledgeService.SearchResult;
import com.irrigaiq.knowledge.KnowledgeService.Status;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge")
@Tag(name = "Knowledge base")
public class KnowledgeController {

	private final KnowledgeService knowledge;

	public KnowledgeController(KnowledgeService knowledge) {
		this.knowledge = knowledge;
	}

	@GetMapping("/search")
	@Operation(summary = "Search the curated agronomy and maintenance notes (with their sources)")
	public SearchResult search(@RequestParam @NotBlank @Size(max = 300) String q,
			@RequestParam(defaultValue = "4") @Min(1) @Max(8) int limit) {
		return knowledge.search(q, limit);
	}

	@GetMapping("/status")
	@Operation(summary = "Documents and chunks indexed, and how many have embeddings")
	public Status status() {
		return knowledge.status();
	}

}
