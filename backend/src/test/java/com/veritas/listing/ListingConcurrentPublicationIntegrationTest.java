package com.veritas.listing;

import com.veritas.common.exception.ListingConflictException;
import com.veritas.property.FurnishingStatus;
import com.veritas.property.Property;
import com.veritas.property.PropertyRepository;
import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import com.veritas.verification.LocationVerificationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3B.6B — real PostgreSQL verification of concurrent publication of two different listings that
 * belong to the SAME property. Runs against an ephemeral Testcontainers PostGIS instance using the exact
 * image already used by the POI/search/3B.4B suites ({@code postgis/postgis:18-3.6}), with Flyway applying
 * all migrations and {@code ddl-auto=validate}. Tagged {@code integration} so the default build never
 * requires Docker while {@code ./mvnw -Pintegration test} runs it. It never touches the development
 * database or any development volume, and cleans up only the rows it inserted (no truncation).
 *
 * <p>Two distinct, complementary levels of proof are provided:</p>
 * <ul>
 *   <li><b>{@link #uniqueViolation_surfacesAsDuplicateKeyAndStaysRolledBack()}</b> deterministically forces
 *       the real {@code uq_active_published_listing_per_property} violation at the exact
 *       {@code saveAndFlush} boundary that {@code ListingService.publishListing()} wraps with its
 *       {@code catch (DuplicateKeyException)}. It captures and inspects the actual exception (top-level
 *       Spring class, nested {@link SQLException}, SQLSTATE, constraint name) and proves the losing
 *       transaction rolls back leaving the listing in its original state. This is the mechanism-level proof
 *       that does not depend on thread timing.</li>
 *   <li><b>{@link #concurrentPublication_exactlyOneWinsAndLoserConflicts()}</b> runs two INDEPENDENT
 *       transactions on separate threads (each Spring {@code @Transactional} call binds its own
 *       thread-confined {@code EntityManager}, so no persistence context or managed entity is shared),
 *       synchronised by a {@link CyclicBarrier} so both reach the publication call simultaneously. It
 *       asserts the end invariant: exactly one listing becomes PUBLISHED, the other is surfaced as the
 *       controlled {@link ListingConflictException} (the HTTP 409 domain conflict) and never an unhandled
 *       integrity exception, and the database holds exactly one active published listing.</li>
 * </ul>
 *
 * <p>The HTTP 409 <em>response envelope</em> mapping from {@code ListingConflictException} is already
 * proven at the controller layer by {@code PropertyControllerSecurityTest#conflictUsesUnifiedEnvelope};
 * this class proves the real database/service behaviour that must feed that mapping.</p>
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@DisplayName("Phase 3B.6B: concurrent publication against real PostGIS (unique index + Spring exception translation)")
class ListingConcurrentPublicationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("veritas.geocoding.enabled", () -> "false");
        registry.add("security.jwt.secret-base64",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci12ZXJpdGFzLXJlbnRhbC1hcHAtMjU2LWJpdC1taW5pbXVtIQ==");
        registry.add("security.jwt.expiration-ms", () -> "3600000");
    }

    private static final String UNIQUE_INDEX = "uq_active_published_listing_per_property";

    @Autowired
    private ListingService listingService;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private PropertyRepository propertyRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private final List<UUID> createdListings = new ArrayList<>();
    private final List<UUID> createdProperties = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUpCreatedRows() {
        // Removes only the rows this test inserted (by generated id); never truncates tables.
        listingRepository.deleteAllById(createdListings);
        propertyRepository.deleteAllById(createdProperties);
        userRepository.deleteAllById(createdUsers);
        createdListings.clear();
        createdProperties.clear();
        createdUsers.clear();
    }

    @Test
    @DisplayName("losing saveAndFlush raises the real 23505 unique violation as a base DataIntegrityViolationException and rolls back")
    void uniqueViolation_surfacesAsDataIntegrityViolationAndStaysRolledBack() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.97, 77.59);
        UUID winner = newDraftListing(property);
        UUID loser = newDraftListing(property);

        // Commit the winner through the real service path so the partial unique index already holds a
        // PUBLISHED entry for this property. The winner is a freshly seeded DRAFT listing at version 0.
        listingService.publishListing(winner, owner.getId(), false, 0);

        // Now force the loser through the SAME write boundary publishListing() guards: load in a fresh
        // independent transaction/persistence context, set PUBLISHED and saveAndFlush. The UPDATE hits
        // uq_active_published_listing_per_property and throws. We capture the raw exception and then roll
        // the transaction back so nothing is persisted.
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            try {
                Listing toPublish = listingRepository.findById(loser).orElseThrow();
                toPublish.setStatus(ListingStatus.PUBLISHED);
                toPublish.setUpdatedAt(OffsetDateTime.now());
                listingRepository.saveAndFlush(toPublish);
            } catch (Throwable t) {
                thrown.set(t);
            }
            status.setRollbackOnly();
        });

        Throwable actual = thrown.get();
        assertNotNull(actual, "saveAndFlush must raise the real unique-violation on the published index");

        // --- Record the ACTUAL exception for the report -------------------------------------------
        SQLException sql = findSqlException(actual);
        assertNotNull(sql, "the exception chain must contain a java.sql.SQLException from PostgreSQL");
        String sqlState = sql.getSQLState();
        System.out.println("[3B.6B][raw-violation] top-level=" + actual.getClass().getName());
        Throwable cause = actual.getCause();
        while (cause != null) {
            System.out.println("[3B.6B][raw-violation]   cause=" + cause.getClass().getName());
            cause = cause.getCause();
        }
        System.out.println("[3B.6B][raw-violation] SQLSTATE=" + sqlState + " message=" + sql.getMessage());

        // PostgreSQL unique violation SQLSTATE is 23505, on the authoritative published-listing index.
        assertEquals("23505", sqlState, "the losing publication must be a PostgreSQL 23505 unique violation");
        assertTrue(sql.getMessage() != null && sql.getMessage().contains(UNIQUE_INDEX),
                "the violated constraint must be the published-listing unique index, was: " + sql.getMessage());

        // This is the crux of Phase 3B.6B: the exception that escapes saveAndFlush is NOT a
        // DuplicateKeyException, so publishListing's original "catch (DuplicateKeyException)" alone would
        // have leaked this as an unhandled HTTP 500. It is the base DataIntegrityViolationException whose
        // cause chain carries the PostgreSQL 23505 + index-name PSQLException -- exactly what the narrow
        // isPublishedListingUniqueViolation() translation now reclassifies into the 409 conflict (proven
        // end-to-end by the concurrent test below).
        assertTrue(actual instanceof DataIntegrityViolationException,
                "the violation must be a Spring DataIntegrityViolationException, was " + actual.getClass().getName());
        assertFalse(actual instanceof DuplicateKeyException,
                "real PostgreSQL surfaces the published-listing unique violation as the BASE "
                        + "DataIntegrityViolationException, not DuplicateKeyException (this is why the narrow "
                        + "translation was required); was " + actual.getClass().getName());

        // Rollback evidence: the losing transaction left no partial status change. Exactly the one
        // winner (committed before the losing attempt) remains published; the loser was never applied.
        assertEquals(1, countPublished(property.getId()),
                "the rolled-back losing attempt must leave exactly the single winner published, not two");
        assertEquals(1, countPublishedFor(property.getId(), winner), "winner stays published");
        assertEquals(0, countPublishedFor(property.getId(), loser), "loser must not be published after rollback");
        Listing loserAfter = listingRepository.findById(loser).orElseThrow();
        assertEquals(ListingStatus.DRAFT, loserAfter.getStatus(),
                "the losing listing must remain in its original non-published state after rollback");

        // The authoritative index must still exist (not weakened).
        assertTrue(uniqueIndexPresent(), UNIQUE_INDEX + " must remain present after the test");
    }

    @Test
    @DisplayName("two concurrent publishListing calls on the same property: exactly one wins, loser conflicts, never a 500")
    void concurrentPublication_exactlyOneWinsAndLoserConflicts() throws Exception {
        User owner = newOwner();
        Property property = newProperty(owner, 12.90, 77.50);
        UUID listingA = newDraftListing(property);
        UUID listingB = newDraftListing(property);

        // Both threads release from the same barrier so both enter the publication race window
        // simultaneously; each publishListing() is @Transactional and therefore runs in its own
        // thread-bound transaction and persistence context. Only immutable UUIDs cross the threads.
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> publishA = publicationTask(barrier, listingA, owner.getId());
            Callable<String> publishB = publicationTask(barrier, listingB, owner.getId());
            Future<String> fa = pool.submit(publishA);
            Future<String> fb = pool.submit(publishB);

            String resultA = fa.get(20, TimeUnit.SECONDS);
            String resultB = fb.get(20, TimeUnit.SECONDS);
            System.out.println("[3B.6B][race] A=" + resultA + " B=" + resultB);

            List<String> results = List.of(resultA, resultB);
            long successes = results.stream().filter("SUCCESS"::equals).count();
            long conflicts = results.stream().filter("CONFLICT"::equals).count();

            // Exactly one winner. No OTHER:* — an OTHER outcome means the real integrity exception escaped
            // publishListing's DuplicateKeyException catch and would have become an unhandled HTTP 500.
            assertEquals(1, successes, "exactly one publication must succeed");
            assertEquals(1, conflicts, "the losing publication must surface as the controlled ListingConflict (409)");
            for (String r : results) {
                assertFalse(r.startsWith("OTHER"),
                        "a raw/unhandled exception leaked to the caller (would be HTTP 500): " + r);
            }

            // Authoritative invariant holds in the database: exactly one active published listing.
            assertEquals(1, countPublished(property.getId()),
                    "the unique index must permit exactly one PUBLISHED listing for the property");

            // The loser remains non-published (its transaction rolled back / was never applied).
            List<UUID> publishedIds = listingRepository.findByPropertyId(property.getId()).stream()
                    .filter(l -> l.getStatus() == ListingStatus.PUBLISHED)
                    .map(Listing::getId)
                    .toList();
            assertEquals(1, publishedIds.size());
            // Both listings exist and are still tracked for cleanup.
            assertTrue(listingRepository.findById(listingA).isPresent());
            assertTrue(listingRepository.findById(listingB).isPresent());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "executor must shut down deterministically");
        }

        assertTrue(uniqueIndexPresent(), UNIQUE_INDEX + " must remain present after the test");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Callable<String> publicationTask(CyclicBarrier barrier, UUID listingId, UUID ownerId) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
                listingService.publishListing(listingId, ownerId, false, 0);
                return "SUCCESS";
            } catch (ListingConflictException ex) {
                return "CONFLICT";
            } catch (Throwable t) {
                return "OTHER:" + t.getClass().getName();
            }
        };
    }

    private int countPublished(UUID propertyId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM listings WHERE property_id = ? AND status = 'PUBLISHED'",
                Integer.class, propertyId);
        return n == null ? 0 : n;
    }

    private int countPublishedFor(UUID propertyId, UUID listingId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM listings WHERE property_id = ? AND id = ? AND status = 'PUBLISHED'",
                Integer.class, propertyId, listingId);
        return n == null ? 0 : n;
    }

    private boolean uniqueIndexPresent() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'listings' AND indexname = ?",
                Integer.class, UNIQUE_INDEX);
        return n != null && n == 1;
    }

    /** Walks the exception cause chain looking for the originating {@link SQLException}. */
    private static SQLException findSqlException(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    // ---- Seed helpers (row-scoped, no truncation) -------------------------------------------------

    private User newOwner() {
        String suffix = UUID.randomUUID().toString();
        User u = new User();
        u.setName("Owner " + suffix);
        u.setEmail("owner-" + suffix + "@example.com");
        u.setPasswordHash("hash");
        u.setRole(UserRole.OWNER);
        u.setVerified(true);
        u.setCreatedAt(OffsetDateTime.now());
        u.setUpdatedAt(OffsetDateTime.now());
        u = userRepository.save(u);
        createdUsers.add(u.getId());
        return u;
    }

    private Property newProperty(User owner, double lat, double lon) {
        Property p = new Property();
        p.setOwner(owner);
        p.setPropertyType("APARTMENT");
        p.setAddress("Secret Address " + UUID.randomUUID());
        p.setLocality("Indiranagar");
        p.setCity("Bengaluru");
        p.setLocation(geometryFactory.createPoint(new Coordinate(lon, lat)));
        p.setLocationVerificationStatus(LocationVerificationStatus.VERIFIED);
        p.setCreatedAt(OffsetDateTime.now());
        p.setUpdatedAt(OffsetDateTime.now());
        p = propertyRepository.save(p);
        createdProperties.add(p.getId());
        return p;
    }

    /**
     * Inserts a fully complete DRAFT listing (satisfies publishListing completeness + every listings CHECK
     * constraint) so a publication attempt is only blocked by the published-listing unique index, not by
     * validation.
     */
    private UUID newDraftListing(Property property) {
        Listing listing = new Listing();
        listing.setProperty(property);
        listing.setRent(new BigDecimal("20000"));
        listing.setDeposit(new BigDecimal("100000"));
        listing.setBhk(2);
        listing.setBathrooms(2);
        listing.setBuiltUpArea(new BigDecimal("1000"));
        listing.setFloor(2);
        listing.setFurnishingStatus(FurnishingStatus.SEMI_FURNISHED);
        listing.setBachelorsAllowed(true);
        listing.setParkingAvailable(true);
        listing.setPetsAllowed(false);
        listing.setAvailableFrom(LocalDate.now().plusDays(7));
        listing.setDescription("Concurrent publication test listing");
        listing.setStatus(ListingStatus.DRAFT);
        listing.setCreatedAt(OffsetDateTime.now());
        listing.setUpdatedAt(OffsetDateTime.now());
        Listing saved = listingRepository.save(listing);
        createdListings.add(saved.getId());
        return saved.getId();
    }
}
