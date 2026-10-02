package com.irrigaiq.common.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Stable pagination envelope for the public API (Spring's {@code Page} JSON is not a contract).
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	public static <T> PageResponse<T> of(Page<T> page) {
		return of(page, Function.identity());
	}

	public static <S, T> PageResponse<T> of(Page<S> page, Function<? super S, ? extends T> mapper) {
		List<T> content = page.getContent().stream().<T>map(mapper).toList();
		return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

}
