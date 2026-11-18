package com.veritas.search;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link PropertySearchRepository} pagination safety (Phase 3B.5). The request DTO
 * already bounds page and size, so an overflowing offset is unreachable via HTTP; these tests pin the
 * repository's defensive guard against a silent {@code long}-to-{@code int} truncation should those
 * bounds ever change, and confirm no query is issued once the offset is rejected.
 */
class PropertySearchRepositoryTest {

    @Test
    @DisplayName("a page offset that would overflow the int range is rejected before any query runs")
    void rejectsOverflowingOffset() {
        EntityManager entityManager = mock(EntityManager.class);
        PropertySearchQueryBuilder queryBuilder = mock(PropertySearchQueryBuilder.class);
        PropertySearchRepository repository = new PropertySearchRepository(entityManager, queryBuilder);

        // offset = 50_000_000 * 100 = 5_000_000_000, which exceeds Integer.MAX_VALUE.
        Pageable overflow = PageRequest.of(50_000_000, 100);

        assertThrows(IllegalArgumentException.class, () -> repository.search(null, overflow));
        verifyNoInteractions(entityManager, queryBuilder);
    }
}
