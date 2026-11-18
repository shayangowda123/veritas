package com.veritas.listing;

import com.veritas.common.exception.ListingConflictException;
import com.veritas.listing.dto.ListingResponse;
import com.veritas.listing.dto.UpdateListingRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3B.6C — real PostgreSQL verification of optimistic locking and lost-update prevention on
 * listings. Runs against an ephemeral Testcontainers PostGIS instance (the same
 * {@code postgis/postgis:18-3.6} image as the 3B.6B suite), Flyway applying all migrations through
 * V6 with {@code ddl-auto=validate}. Tagged {@code integration} so the default build never requires
 * Docker. It never touches the development database and cleans up only the rows it inserts.
 *
 * <p>The three complementary levels of proof mirror the two protection mechanisms plus the
 * Hibernate-owned increment guarantee:</p>
 * <ul>
 *   <li><b>Test A (overlapping transactions)</b> — two threads load the same listing at the same
 *       version (synchronised by a {@link CyclicBarrier} so the overlap is deterministic, not a
 *       sleep race). The winner commits a real versioned UPDATE; the loser, whose loaded row is now
 *       stale, must fail with {@link ObjectOptimisticLockingFailureException} (the safety-net 409
 *       mapping in {@code GlobalExceptionHandler}). Exactly one write's effect survives, and the
 *       version increases by exactly one — a silent lost update is impossible.</li>
 *   <li><b>Test B (sequential stale client)</b> — the case {@code @Version} alone cannot catch: an
 *       update commits v0 to v1, then a second request still carrying v0 is rejected by the service
 *       expected-version gate with the controlled {@link ListingConflictException} (HTTP 409), and
 *       the row is proven byte-for-byte untouched.</li>
 *   <li><b>Test C (publish/status increments)</b> — publish and direct status changes increment the
 *       version exactly once, responses and the database expose the NEW version to the client,
 *       reusing the old version is rejected, and the PUBLISHED delegation path through
 *       {@code updateListingStatus} does not lose the expected version.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@DisplayName("Phase 3B.6C: optimistic locking against real PostGIS (@Version overlap + expected-version gate)")
class ListingOptimisticLockingIntegrationTest {

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
    @DisplayName("Test A: overlapping transactions on one listing -- winner commits, loser gets the optimistic-lock exception, version rises exactly once")
    void overlappingTransactions_oneWinsOtherGetsOptimisticLockException() throws Exception {
        User owner = newOwner();
        Property property = newProperty(owner, 12.96, 77.56);
        UUID listingId = newDraftListing(property);
        int versionBefore = versionOf(listingId);

        // Deterministic overlap: the loser opens a transaction and loads the row (a plain SELECT does
        // not lock it), then parks BEFORE flushing until the winner has fully committed. When the loser
        // finally flushes, its versioned UPDATE (WHERE version = stale) matches zero rows and Spring's
        // JPA transaction translation raises ObjectOptimisticLockingFailureException -- the exact real
        // overlapping-transaction failure the Phase 3B.6C safety-net handler maps to HTTP 409.
        CountDownLatch loserLoaded = new CountDownLatch(1);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> winnerOutcome = new AtomicReference<>();
        AtomicReference<Throwable> loserThrown = new AtomicReference<>();
        try {
            // Winner runs on the pool thread only after the loser has loaded the stale row.
            Future<?> winner = pool.submit(() -> {
                awaitQuietly(loserLoaded);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.executeWithoutResult(status -> {
                    Listing managed = listingRepository.findById(listingId).orElseThrow();
                    managed.setDescription("WINNER-EDIT");
                    managed.setUpdatedAt(OffsetDateTime.now());
                    listingRepository.saveAndFlush(managed);
                });
                winnerCommitted.countDown();
                winnerOutcome.set("SUCCESS");
            });

            // Loser holds one open Spring transaction spanning the winner's commit, on this thread.
            TransactionTemplate loserTx = new TransactionTemplate(transactionManager);
            try {
                loserTx.executeWithoutResult(status -> {
                    Listing stale = listingRepository.findById(listingId).orElseThrow();
                    stale.setDescription("LOSER-EDIT");
                    stale.setUpdatedAt(OffsetDateTime.now());
                    loserLoaded.countDown();        // let the winner load + commit first
                    awaitQuietly(winnerCommitted);   // now the DB version has advanced past `stale`
                    listingRepository.saveAndFlush(stale); // UPDATE ... WHERE version = stale -> 0 rows
                });
            } catch (Throwable t) {
                loserThrown.set(t);
            }
            winner.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "executor must shut down deterministically");
        }

        assertEquals("SUCCESS", winnerOutcome.get(), "the winner's versioned UPDATE must commit");

        // The loser must fail with the exact exception the Phase 3B.6C safety-net handler maps to 409.
        Throwable thrown = loserThrown.get();
        assertNotNull(thrown, "the stale overlapping write must be rejected, not silently applied");
        assertTrue(thrown instanceof ObjectOptimisticLockingFailureException,
                "loser must surface Spring's ObjectOptimisticLockingFailureException, was: "
                        + thrown.getClass().getName());

        // No silent lost update: the winner's effect survives, the loser's never touches the row.
        Listing finalRow = listingRepository.findById(listingId).orElseThrow();
        assertEquals("WINNER-EDIT", finalRow.getDescription(),
                "only the winning write may remain; the loser was rejected, not merged or overwritten");

        // Hibernate incremented the version exactly once despite two write attempts.
        assertEquals(versionBefore + 1, versionOf(listingId),
                "@Version must increase by exactly one for a single committed UPDATE");
    }

    @Test
    @DisplayName("Test B: sequential stale client -- committed v0->v1, then a request carrying v0 conflicts and the row stays byte-for-byte untouched")
    void sequentialStaleClient_expectedVersionGateRejectsWithoutTouchingRow() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.95, 77.55);
        UUID listingId = newDraftListing(property);
        assertEquals(0, versionOf(listingId), "a freshly persisted listing starts at version 0 (Hibernate-assigned)");

        // First mutation: expected version 0 matches; the service flushes before mapping, so the
        // response must already carry the NEW version.
        ListingResponse first = listingService.updateListing(listingId, owner.getId(), false, updateRequest(0));
        assertEquals(1, first.getVersion(), "update response must expose the incremented version");
        assertEquals(1, versionOf(listingId));
        assertEquals(new BigDecimal("21000.00"), rentOf(listingId));

        // Second request with the ORIGINAL version: @Version alone cannot catch this (its transaction
        // begins after the first committed, so the freshly loaded row would match whatever Hibernate
        // holds). The expected-version gate must reject it with the controlled 409 conflict.
        ListingResponse snapshotBefore = listingService.getListingDetails(listingId, owner.getId(), true);
        ListingConflictException conflict = assertThrows(ListingConflictException.class, () ->
                listingService.updateListing(listingId, owner.getId(), false, updateRequest(0)));
        assertEquals("Listing has been modified. Please refresh and try again.", conflict.getMessage());

        // The rejected request changed NOTHING: version, rent and description are identical to the
        // state captured before the attempt.
        assertEquals(1, versionOf(listingId), "stale-client rejection must not increment the version");
        assertEquals(new BigDecimal("21000.00"), rentOf(listingId));
        assertEquals(snapshotBefore.getDescription(),
                listingService.getListingDetails(listingId, owner.getId(), true).getDescription());

        // A request carrying the CURRENT version still succeeds and advances the version again.
        ListingResponse retry = listingService.updateListing(listingId, owner.getId(), false, updateRequest(1));
        assertEquals(2, retry.getVersion());
        assertEquals(2, versionOf(listingId));
    }

    @Test
    @DisplayName("Test C: publish and status change increment the version once; reusing an old version conflicts, including via the PUBLISHED delegation path")
    void publishAndStatus_incrementsVersion_andRejectsReusedOldVersion() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.94, 77.54);
        UUID listingId = newDraftListing(property);

        // Publish (DRAFT -> PUBLISHED) increments 0 -> 1 and the response carries the NEW version.
        ListingResponse published = listingService.publishListing(listingId, owner.getId(), false, 0);
        assertEquals(ListingStatus.PUBLISHED, published.getStatus());
        assertEquals(1, published.getVersion(), "publish response must expose the incremented version");
        assertEquals(1, versionOf(listingId));

        // Reusing the pre-publish version is rejected -- the idempotent already-PUBLISHED return must
        // not bypass the expected-version gate.
        assertThrows(ListingConflictException.class, () ->
                listingService.publishListing(listingId, owner.getId(), false, 0));
        assertEquals(1, versionOf(listingId), "rejected publish must not change the row");

        // Direct status change PUBLISHED -> UNAVAILABLE increments 1 -> 2.
        ListingResponse unavailable = listingService.updateListingStatus(
                listingId, owner.getId(), false, ListingStatus.UNAVAILABLE, 1);
        assertEquals(ListingStatus.UNAVAILABLE, unavailable.getStatus());
        assertEquals(2, unavailable.getVersion());
        assertEquals(2, versionOf(listingId));
        assertThrows(ListingConflictException.class, () ->
                listingService.updateListingStatus(listingId, owner.getId(), false, ListingStatus.UNAVAILABLE, 1));

        // Delegation path: status change targeting PUBLISHED must forward the CURRENT expected version
        // to publishListing -- carrying the old (post-publish) version 1 conflicts...
        assertThrows(ListingConflictException.class, () ->
                listingService.updateListingStatus(listingId, owner.getId(), false, ListingStatus.PUBLISHED, 1));
        assertEquals(2, versionOf(listingId));

        // ...while the correct version 2 publishes cleanly and increments to 3.
        ListingResponse republished = listingService.updateListingStatus(
                listingId, owner.getId(), false, ListingStatus.PUBLISHED, 2);
        assertEquals(ListingStatus.PUBLISHED, republished.getStatus());
        assertEquals(3, republished.getVersion());
        assertEquals(3, versionOf(listingId));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Parks the current thread until {@code latch} trips, failing the test on interrupt/timeout. */
    private void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS), "latch must trip within the timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while synchronising the overlap", e);
        }
    }

    private UpdateListingRequest updateRequest(int version) {
        UpdateListingRequest r = new UpdateListingRequest();
        r.setRent(new BigDecimal("21000.00"));
        r.setDeposit(new BigDecimal("100000.00"));
        r.setBhk(2);
        r.setBathrooms(2);
        r.setBuiltUpArea(new BigDecimal("1000.00"));
        r.setFloor(3);
        r.setFurnishingStatus(FurnishingStatus.SEMI_FURNISHED);
        r.setBachelorsAllowed(true);
        r.setParkingAvailable(true);
        r.setPetsAllowed(false);
        r.setAvailableFrom(LocalDate.now().plusDays(7));
        r.setDescription("Expected-version update");
        r.setVersion(version);
        return r;
    }

    /** Authoritative DB read: bypasses any persistence context so assertions see committed state. */
    private int versionOf(UUID listingId) {
        Integer v = jdbcTemplate.queryForObject("SELECT version FROM listings WHERE id = ?",
                Integer.class, listingId);
        assertNotNull(v, "listing row must exist with a version column (V6 migration)");
        return v;
    }

    private BigDecimal rentOf(UUID listingId) {
        return jdbcTemplate.queryForObject("SELECT rent FROM listings WHERE id = ?",
                BigDecimal.class, listingId);
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
     * Inserts a fully complete DRAFT listing at Hibernate's initial version 0 (the service never
     * assigns a version manually -- the seed relies on the same @Version lifecycle).
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
        listing.setDescription("Optimistic locking test listing");
        listing.setStatus(ListingStatus.DRAFT);
        listing.setCreatedAt(OffsetDateTime.now());
        listing.setUpdatedAt(OffsetDateTime.now());
        Listing saved = listingRepository.save(listing);
        createdListings.add(saved.getId());
        return saved.getId();
    }
}
