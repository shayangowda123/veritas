package com.veritas.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Guards the owner "my" paginated endpoints against unsafe client-controlled sorting and
 * unbounded page numbers (Phase 3B.5).
 *
 * <p>Design guarantees:</p>
 * <ul>
 *     <li>Only explicitly whitelisted sort fields are accepted; unknown and nested paths
 *         (for example {@code owner.name} or {@code property.city}, which contain a dot and are
 *         never in the whitelist) are rejected before any query is built. This keeps a bad sort
 *         from reaching Spring Data/JPA, where it would otherwise surface as an unhandled
 *         {@code PropertyReferenceException} (HTTP 500).</li>
 *     <li>Every supplied sort field is validated, so one invalid field in a multi-field request
 *         is rejected; valid fields keep their requested directions.</li>
 *     <li>Page numbers are bounded ({@code 0..MAX_PAGE}) and validated here rather than relying on
 *         the {@link Pageable} resolver, so a huge page cannot drive an unbounded/overflowing offset.
 *         With {@code MAX_PAGE} and the resolver's {@code max-page-size} both bounded, the resulting
 *         offset always fits the JPA integer offset range.</li>
 *     <li>Ordering is made deterministic by appending a unique {@code id ASC} tie-breaker unless the
 *         caller already sorted by {@code id}, preventing unstable page boundaries when the primary
 *         sort column is tied (for example duplicate {@code createdAt}).</li>
 * </ul>
 *
 * <p>Validation runs in the controller <em>before</em> any repository access, so a genuine
 * database failure is never reclassified as a client error: only the checks below throw. Violations
 * are reported as {@link IllegalArgumentException}, which the shared {@code GlobalExceptionHandler}
 * already maps to the controlled HTTP 400 envelope. Invalid sort <em>directions</em> are rejected
 * earlier, during {@link Pageable} resolution, and reach the same 400 handler.</p>
 */
public final class OwnerPaginationGuard {

    /** Maximum page index accepted by the owner endpoints (owners have finite inventories). */
    public static final int MAX_PAGE = 1000;

    /** The entity identifier used as the stable, unique ordering tie-breaker. */
    private static final String ID_FIELD = "id";

    /** Default ordering applied when no sort parameter is supplied. */
    private static final String DEFAULT_SORT_FIELD = "createdAt";

    private OwnerPaginationGuard() {
    }

    /**
     * Validates the requested page against the bound and the resolved {@link Pageable}'s sort
     * against the supplied whitelist, then returns a normalized {@link Pageable} that always ends
     * with a deterministic {@code id ASC} tie-breaker (unless the caller already sorts by
     * {@code id}).
     *
     * <p>The raw {@code requestedPage} query parameter is inspected explicitly because Spring Data's
     * {@link Pageable} resolver silently coerces a negative page to {@code 0}; without the raw value
     * a negative page could not be rejected. When it is absent the resolved page number is used.</p>
     *
     * @param pageable          the {@link Pageable} produced by Spring Data's argument resolver
     * @param requestedPage     the raw {@code page} request parameter (may be {@code null})
     * @param allowedSortFields the whitelist of direct entity attribute names accepted for sorting
     * @return a validated, tie-broken {@link Pageable} safe to pass to the repository
     * @throws IllegalArgumentException for an out-of-range page or a non-whitelisted sort field
     */
    public static Pageable validateAndNormalize(Pageable pageable, Integer requestedPage, Set<String> allowedSortFields) {
        int page = (requestedPage != null) ? requestedPage : pageable.getPageNumber();
        if (page < 0) {
            throw new IllegalArgumentException("page must be zero or greater");
        }
        if (page > MAX_PAGE) {
            throw new IllegalArgumentException("page must not exceed " + MAX_PAGE);
        }

        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Direction.DESC, DEFAULT_SORT_FIELD);
        } else {
            for (Sort.Order order : sort) {
                if (!allowedSortFields.contains(order.getProperty())) {
                    throw new IllegalArgumentException("Unsupported sort field: " + order.getProperty());
                }
            }
        }

        return PageRequest.of(page, pageable.getPageSize(), withIdTieBreaker(sort));
    }

    private static Sort withIdTieBreaker(Sort sort) {
        if (sort.getOrderFor(ID_FIELD) != null) {
            return sort;
        }
        return sort.and(Sort.by(Sort.Direction.ASC, ID_FIELD));
    }
}
