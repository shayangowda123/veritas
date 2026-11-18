package com.veritas.listing;

import com.veritas.listing.dto.ListingResponse;
import com.veritas.listing.dto.UpdateListingRequest;
import com.veritas.common.exception.InvalidStateTransitionException;
import com.veritas.property.FurnishingStatus;
import com.veritas.property.Property;
import com.veritas.property.PropertyRepository;
import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import com.veritas.verification.LocationVerificationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 3B.6D — real PostgreSQL verification of centralized listing-lifecycle enforcement. Runs against
 * an ephemeral Testcontainers PostGIS instance (the same {@code postgis/postgis:18-3.6} image as the
 * 3B.6B/3B.6C suites), Flyway applying all migrations through V6 with {@code ddl-auto=validate}. Tagged
 * {@code integration} so the default build never requires Docker. It never touches the development
 * database and cleans up only the rows it inserts (no truncation).
 *
 * <p>Every assertion reads committed state back through {@link JdbcTemplate} so a rejected operation is
 * proven to leave the row byte-for-byte unchanged (business fields, status and optimistic-lock version).
 * Tests drive listings to RENTED through the REAL service, then exercise each lifecycle-changing
 * operation to prove both the dedicated publish endpoint path ({@code publishListing}) and the status
 * endpoint delegation ({@code updateListingStatus}) obey the same centralized policy — the status
 * endpoint cannot bypass the RENTED -> PUBLISHED rejection.</p>
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@DisplayName("Phase 3B.6D: listing lifecycle enforcement against real PostGIS (RENTED/ARCHIVED frozen, no bypass)")
class ListingLifecycleIntegrationTest {

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
    @DisplayName("Test A: RENTED listing edit is rejected (400) and the row is byte-for-byte unchanged")
    void rentedEditRejected_rowUnchanged() {
        Fixture f = newRentedFixture();

        RowSnapshot before = snapshot(f.listingId);
        assertEquals("RENTED", before.status);

        // Attempt a full business-field edit carrying the CURRENT expected version, so the failure can
        // only come from the lifecycle/edit policy (not authorization or a stale version).
        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListing(f.listingId, f.ownerId, false, editRequest(before.version)));

        RowSnapshot after = snapshot(f.listingId);
        assertEquals(before, after, "a rejected edit must not change any business field, status or version");
    }

    @Test
    @DisplayName("Test B: RENTED listing direct publish is rejected (400) and the row is unchanged")
    void rentedDirectPublishRejected_rowUnchanged() {
        Fixture f = newRentedFixture();
        RowSnapshot before = snapshot(f.listingId);

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.publishListing(f.listingId, f.ownerId, false, before.version));

        RowSnapshot after = snapshot(f.listingId);
        assertEquals(before, after, "RENTED -> PUBLISHED via the publish operation must not write");
    }

    @Test
    @DisplayName("Test C: RENTED -> PUBLISHED via the status endpoint cannot bypass the publish policy")
    void rentedPublishViaStatusDelegationRejected() {
        Fixture f = newRentedFixture();
        RowSnapshot before = snapshot(f.listingId);

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListingStatus(f.listingId, f.ownerId, false, ListingStatus.PUBLISHED, before.version));

        RowSnapshot after = snapshot(f.listingId);
        assertEquals(before, after,
                "the status endpoint must funnel through the same centralized policy, never reaching the publish flush");
    }

    @Test
    @DisplayName("Test D: RENTED -> ARCHIVED is allowed, status changes and the version increments exactly once")
    void rentedToArchivedAllowed_incrementsVersionOnce() {
        Fixture f = newRentedFixture();
        RowSnapshot before = snapshot(f.listingId);

        ListingResponse response = listingService.updateListingStatus(
                f.listingId, f.ownerId, false, ListingStatus.ARCHIVED, before.version);

        assertEquals(ListingStatus.ARCHIVED, response.getStatus());
        RowSnapshot after = snapshot(f.listingId);
        assertEquals("ARCHIVED", after.status);
        assertEquals(before.version + 1, after.version,
                "@Version must increase by exactly one for the single committed archive UPDATE");
    }

    @Test
    @DisplayName("Test E: valid same-state requests are no-ops that do not write or increment the version")
    void sameStateIsNoOp_persisted() {
        Fixture f = newRentedFixture();
        RowSnapshot rentedBefore = snapshot(f.listingId);

        // RENTED -> RENTED no-op.
        listingService.updateListingStatus(f.listingId, f.ownerId, false, ListingStatus.RENTED, rentedBefore.version);
        assertEquals(rentedBefore, snapshot(f.listingId),
                "same-state RENTED no-op must leave status and version unchanged");

        // PUBLISHED -> PUBLISHED no-op via the publish operation.
        User owner = getUser(f.ownerId);
        UUID draftId = newDraftListing(newProperty(owner, 12.93, 77.53));
        listingService.publishListing(draftId, owner.getId(), false, 0); // DRAFT -> PUBLISHED (v0 -> v1)
        RowSnapshot publishedBefore = snapshot(draftId);
        assertEquals("PUBLISHED", publishedBefore.status);

        listingService.publishListing(draftId, owner.getId(), false, publishedBefore.version); // idempotent no-op
        assertEquals(publishedBefore, snapshot(draftId),
                "publishing an already-PUBLISHED listing must not write or increment the version");
    }

    // ---- fixtures / helpers ----------------------------------------------------------------------

    private static final class Fixture {
        final UUID ownerId;
        final UUID listingId;
        final UUID propertyId;
        Fixture(UUID ownerId, UUID propertyId, UUID listingId) {
            this.ownerId = ownerId;
            this.propertyId = propertyId;
            this.listingId = listingId;
        }
    }

    /** Immutable projection of the persisted columns relevant to lifecycle/locking assertions. */
    private static final class RowSnapshot {
        final String status;
        final int version;
        final BigDecimal rent;
        final String description;
        RowSnapshot(String status, int version, BigDecimal rent, String description) {
            this.status = status;
            this.version = version;
            this.rent = rent;
            this.description = description;
        }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RowSnapshot other)) {
                return false;
            }
            return version == other.version
                    && status.equals(other.status)
                    && rent.compareTo(other.rent) == 0
                    && java.util.Objects.equals(description, other.description);
        }
        @Override
        public int hashCode() {
            return java.util.Objects.hash(status, version, rent, description);
        }
        @Override
        public String toString() {
            return "RowSnapshot{status=" + status + ", version=" + version
                    + ", rent=" + rent + ", description=" + description + "}";
        }
    }

    private Fixture newRentedFixture() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.92, 77.52);
        UUID listingId = newDraftListing(property);
        // Drive to RENTED through the real service: DRAFT -> PUBLISHED (v0->v1) -> RENTED (v1->v2).
        listingService.publishListing(listingId, owner.getId(), false, 0);
        listingService.updateListingStatus(listingId, owner.getId(), false, ListingStatus.RENTED, 1);
        return new Fixture(owner.getId(), property.getId(), listingId);
    }

    private RowSnapshot snapshot(UUID listingId) {
        return jdbcTemplate.query(
                "SELECT status, version, rent, description FROM listings WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        throw new IllegalStateException("listing row missing for id " + listingId);
                    }
                    return new RowSnapshot(rs.getString("status"), rs.getInt("version"),
                            rs.getBigDecimal("rent"), rs.getString("description"));
                },
                listingId);
    }

    private UpdateListingRequest editRequest(int currentVersion) {
        UpdateListingRequest r = new UpdateListingRequest();
        r.setRent(new BigDecimal("99999.00"));
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
        r.setDescription("SHOULD-NOT-PERSIST");
        r.setVersion(currentVersion);
        return r;
    }

    private User getUser(UUID id) {
        return userRepository.findById(id).orElseThrow();
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
        listing.setDescription("Lifecycle test listing");
        listing.setStatus(ListingStatus.DRAFT);
        listing.setCreatedAt(OffsetDateTime.now());
        listing.setUpdatedAt(OffsetDateTime.now());
        Listing saved = listingRepository.save(listing);
        createdListings.add(saved.getId());
        assertNotNull(saved.getId());
        return saved.getId();
    }
}
