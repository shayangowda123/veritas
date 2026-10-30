package com.veritas.search.dto;

import java.util.List;

/**
 * A stable, explicit pagination envelope for public discovery responses.
 *
 * <p>Preferred over serializing Spring's {@code Page} directly so the JSON contract remains
 * predictable across Spring Data releases.</p>
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
