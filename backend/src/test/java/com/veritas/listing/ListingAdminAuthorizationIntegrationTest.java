package com.veritas.listing;

import com.veritas.common.exception.InvalidStateTransitionException;
import com.veritas.common.exception.ListingConflictException;
import com.veritas.common.exception.UnauthorizedActionException;
import com.veritas.listing.dto.CreateListingRequest;
import com.veritas.listing.dto.ListingResponse;
import com.veritas.listing.dto.UpdateListingRequest;
import com.veritas.property.FurnishingStatus;
import com.veritas.property.Property;
import com.veritas.property.PropertyRepository;
import com.veritas.property.PropertyService;
import com.veritas.property.dto.PropertyResponse;
import com.veritas.property.dto.UpdatePropertyRequest;
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
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 3B.6E — real PostgreSQL/PostGIS verification of ADMIN authorization across the property,
 * listing and status workflows. Uses the same ephemeral {@code postgis/postgis:18-3.6} Testcontainer,
 * Flyway-through-V6 and {@code ddl-auto=validate} setup as the 3B.6B/3B.6C/3B.6D suites, is tagged
 * {@code integration} so the default build never requires Docker, and cleans up only the rows it
 * inserts (no truncation, never touches the development database).
 *
 * <p>Proven against committed state (read back through {@link JdbcTemplate}): an ADMIN may update
 * another owner's property WITHOUT transferring ownership, create/update/publish/change-status on
 * another owner's listing, and the status endpoint's PUBLISHED delegation forwards the ADMIN flag so
 * it succeeds with exactly one version increment. ADMIN does NOT bypass the Phase 3B.6C optimistic
 * locking or the Phase 3B.6D lifecycle gates (stale version -> 409; RENTED/ARCHIVED edits and
 * publishes -> 400, rows byte-for-byte unchanged). Cross-owner OWNER attempts and independent
 * second-owner mutations remain rejected (403) or isolated, and every successful mutation increments
 * the persisted version exactly once.</p>
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@DisplayName("Phase 3B.6E: ADMIN authorization against real PostGIS (bypasses ownership only, never lifecycle/locking)")
class ListingAdminAuthorizationIntegrationTest {

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
    private PropertyService propertyService;
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
    @DisplayName("Test A: ADMIN updates another owner's property; ownership unchanged and owner's listing flow still works")
    void adminUpdatesOthersProperty_ownershipNotTransferred() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.91, 77.51);
        UUID listingId = newDraftListing(property);
        String originalAddress = property.getAddress();

        PropertyResponse response = propertyService.updateProperty(
                property.getId(), admin().getId(), true, updatePropertyRequest(originalAddress + " (admin edit)"));

        assertEquals(owner.getId(), response.getOwnerId(), "ADMIN edit must not transfer property ownership");
        assertEquals(owner.getId(), propertyOwnerId(property.getId()),
                "persisted owner_id must still be the original owner");

        // The genuine owner retains full control over the listing on the administered property.
        ListingResponse published = listingService.publishListing(listingId, owner.getId(), false, 0);
        assertEquals(ListingStatus.PUBLISHED, published.getStatus());
        assertEquals("PUBLISHED", snapshot(listingId).status);
    }

    @Test
    @DisplayName("Test B: ADMIN creates a DRAFT listing on another owner's property (stays on that property)")
    void adminCreatesDraftOnOthersProperty() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.93, 77.53);

        ListingResponse response = listingService.createDraftListing(admin().getId(), true, createListingRequest(property));

        assertEquals(ListingStatus.DRAFT, response.getStatus());
        assertEquals(property.getId(), response.getPropertyId(), "listing must remain on the original property");
        createdListings.add(response.getId()); // @Id is generated at save, so this matches the entity's id
        assertEquals(owner.getId(), listingPropertyOwnerId(response.getId()),
                "the new listing resolves to the original owner's property (no ownership side-channel)");
    }

    @Test
    @DisplayName("Test C: ADMIN updates another owner's draft listing; version increments exactly once")
    void adminUpdatesOthersListing() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.94, 77.54);
        UUID listingId = newDraftListing(property);
        RowSnapshot before = snapshot(listingId);

        ListingResponse response = listingService.updateListing(
                listingId, admin().getId(), true, validEditRequest(before.version));

        assertEquals(before.version + 1, response.getVersion(), "response exposes the newly persisted version");
        RowSnapshot after = snapshot(listingId);
        assertEquals(before.version + 1, after.version, "exactly one @Version increment for the committed UPDATE");
        assertEquals(new BigDecimal("31313.00"), after.rent);
        assertEquals("ADMIN edited description", after.description);
    }

    @Test
    @DisplayName("Test D: ADMIN publishes another owner's listing; second owner's mutation remains independently allowed")
    void adminPublishesOthersListing_independentOwnerUntouched() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.95, 77.55);
        UUID listingId = newDraftListing(property);

        // Independent second owner on a different property/listing must still be allowed to publish.
        User otherOwner = newOwner();
        Property otherProperty = newProperty(otherOwner, 13.05, 77.65);
        UUID otherListingId = newDraftListing(otherProperty);

        ListingResponse published = listingService.publishListing(listingId, admin().getId(), true, 0);
        assertEquals(ListingStatus.PUBLISHED, published.getStatus());
        assertEquals(1, snapshot(listingId).version, "DRAFT -> PUBLISHED flush increments the version once");

        ListingResponse otherPublished = listingService.publishListing(otherListingId, otherOwner.getId(), false, 0);
        assertEquals(ListingStatus.PUBLISHED, otherPublished.getStatus());
        assertEquals(1, snapshot(otherListingId).version,
                "the second owner's mutation is independent and must not consume the first listing's unique-index guard");
    }

    @Test
    @DisplayName("Test E: status-endpoint PUBLISHED delegation forwards the ADMIN flag and flushes exactly once")
    void adminStatusDelegationToPublishSucceeds_once() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.96, 77.56);
        UUID listingId = newDraftListing(property);

        ListingResponse response = listingService.updateListingStatus(
                listingId, admin().getId(), true, ListingStatus.PUBLISHED, 0);

        assertEquals(ListingStatus.PUBLISHED, response.getStatus());
        RowSnapshot after = snapshot(listingId);
        assertEquals("PUBLISHED", after.status);
        assertEquals(1, after.version,
                "the delegated publishListing performs the single flush; the status path must not write again");
    }

    @Test
    @DisplayName("Test F: ADMIN holding a stale version is still rejected (409) on every mutation path")
    void adminStaleVersionStillRejected() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.97, 77.57);
        UUID listingId = newDraftListing(property);
        // Owner bumps the version legitimately: DRAFT -> PUBLISHED (v0 -> v1).
        listingService.publishListing(listingId, owner.getId(), false, 0);
        RowSnapshot before = snapshot(listingId);
        assertEquals(1, before.version);

        // ADMIN asserting the outdated version 0 must fail on all three mutation paths.
        assertThrows(ListingConflictException.class, () ->
                listingService.publishListing(listingId, admin().getId(), true, 0));
        assertThrows(ListingConflictException.class, () ->
                listingService.updateListingStatus(listingId, admin().getId(), true, ListingStatus.UNAVAILABLE, 0));
        assertThrows(ListingConflictException.class, () ->
                listingService.updateListing(listingId, admin().getId(), true, validEditRequest(0)));

        assertEquals(before, snapshot(listingId),
                "rejected ADMIN mutations must leave business fields, status and version untouched");
    }

    @Test
    @DisplayName("Test G: ADMIN cannot bypass RENTED/ARCHIVED lifecycle restrictions (400, row unchanged)")
    void adminCannotBypassLifecycleRestrictions() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.98, 77.58);
        UUID listingId = newDraftListing(property);
        // Drive to RENTED through the real service: DRAFT -> PUBLISHED (v0->v1) -> RENTED (v1->v2).
        listingService.publishListing(listingId, owner.getId(), false, 0);
        listingService.updateListingStatus(listingId, owner.getId(), false, ListingStatus.RENTED, 1);
        RowSnapshot rented = snapshot(listingId);
        assertEquals("RENTED", rented.status);

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListing(listingId, admin().getId(), true, validEditRequest(rented.version)));
        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.publishListing(listingId, admin().getId(), true, rented.version));
        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListingStatus(listingId, admin().getId(), true, ListingStatus.PUBLISHED, rented.version));

        assertEquals(rented, snapshot(listingId), "a lifecycle-rejected ADMIN mutation must not write");

        // ARCHIVED is terminal: ADMIN holds the current version but the transition table denies everything.
        ListingResponse archived = listingService.updateListingStatus(
                listingId, admin().getId(), true, ListingStatus.ARCHIVED, rented.version);
        assertEquals(ListingStatus.ARCHIVED, archived.getStatus());
        RowSnapshot archivedRow = snapshot(listingId);
        assertEquals(rented.version + 1, archivedRow.version, "the allowed RENTED -> ARCHIVED write flushes once");

        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.publishListing(listingId, admin().getId(), true, archivedRow.version));
        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListing(listingId, admin().getId(), true, validEditRequest(archivedRow.version)));
        assertThrows(InvalidStateTransitionException.class, () ->
                listingService.updateListingStatus(listingId, admin().getId(), true, ListingStatus.DRAFT, archivedRow.version));

        assertEquals(archivedRow, snapshot(listingId), "ARCHIVED is terminal for ADMIN exactly as for the owner");
    }

    @Test
    @DisplayName("Test H: cross-owner OWNER attempts remain rejected (403) on every listing mutation path")
    void crossOwnerOwnerActorRemainsRejected() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.99, 77.59);
        UUID listingId = newDraftListing(property);

        User stranger = newOwner();
        newProperty(stranger, 13.09, 77.69); // stranger genuinely owns a different property
        RowSnapshot before = snapshot(listingId);

        assertThrows(UnauthorizedActionException.class, () ->
                listingService.updateListing(listingId, stranger.getId(), false, validEditRequest(before.version)));
        assertThrows(UnauthorizedActionException.class, () ->
                listingService.publishListing(listingId, stranger.getId(), false, before.version));
        assertThrows(UnauthorizedActionException.class, () ->
                listingService.updateListingStatus(listingId, stranger.getId(), false, ListingStatus.PUBLISHED, before.version));
        assertThrows(UnauthorizedActionException.class, () ->
                listingService.createDraftListing(stranger.getId(), false, createListingRequest(property)));

        assertEquals(before, snapshot(listingId),
                "authorization is checked before version/lifecycle; a 403 must never write");

        // A RENTER actor (isAdmin=false, not the owner) is denied identically.
        User renter = newRenter();
        assertThrows(UnauthorizedActionException.class, () ->
                listingService.publishListing(listingId, renter.getId(), false, before.version));
    }

    @Test
    @DisplayName("Test I: every successful mutation increments the persisted version exactly once (owner and admin paths)")
    void versionIncrementsExactlyOncePerSuccessfulMutation() {
        User owner = newOwner();
        Property property = newProperty(owner, 12.90, 77.60);
        UUID listingId = newDraftListing(property);

        int expected = 0;
        assertEquals(expected, snapshot(listingId).version, "fresh DRAFT persists version 0");

        listingService.updateListing(listingId, admin().getId(), true, validEditRequest(expected));
        assertEquals(++expected, snapshot(listingId).version, "ADMIN edit: +1");

        listingService.updateListingStatus(listingId, owner.getId(), false, ListingStatus.PUBLISHED, expected);
        // Owner publishes through the delegated path; single flush -> +1.
        assertEquals(++expected, snapshot(listingId).version, "OWNER publish via delegation: +1");

        listingService.updateListingStatus(listingId, owner.getId(), false, ListingStatus.UNAVAILABLE, expected);
        // DRAFT -> UNAVAILABLE is not a legal transition; PUBLISHED -> UNAVAILABLE is, and the direct
        // (non-delegated) status flush adds exactly +1.
        assertEquals(++expected, snapshot(listingId).version, "OWNER status change (direct flush): +1");

        listingService.updateListingStatus(listingId, admin().getId(), true, ListingStatus.PUBLISHED, expected);
        assertEquals(++expected, snapshot(listingId).version,
                "ADMIN re-publish via delegation: +1 exactly once (no double flush)");

        // Same-state no-ops must not increment for ADMIN either.
        RowSnapshot published = snapshot(listingId);
        listingService.publishListing(listingId, admin().getId(), true, published.version);
        listingService.updateListingStatus(listingId, admin().getId(), true, ListingStatus.PUBLISHED, published.version);
        assertEquals(published, snapshot(listingId), "same-state no-ops write nothing for ADMIN actors");
    }

    // ---- fixtures / helpers ----------------------------------------------------------------------

    private User admin() {
        // Models trusted out-of-band ADMIN provisioning: no public registration path can create this
        // role (Phase 3B.6E registration hardening), so the fixture saves the entity directly.
        String suffix = UUID.randomUUID().toString();
        User u = new User();
        u.setName("Admin " + suffix);
        u.setEmail("admin-" + suffix + "@example.com");
        u.setPasswordHash("hash");
        u.setRole(UserRole.ADMIN);
        u.setVerified(true);
        u.setCreatedAt(OffsetDateTime.now());
        u.setUpdatedAt(OffsetDateTime.now());
        u = userRepository.save(u);
        createdUsers.add(u.getId());
        return u;
    }

    private UpdatePropertyRequest updatePropertyRequest(String address) {
        UpdatePropertyRequest r = new UpdatePropertyRequest();
        r.setPropertyType("APARTMENT");
        r.setAddress(address);
        r.setLocality("Indiranagar");
        r.setCity("Bengaluru");
        r.setLatitude(12.91);
        r.setLongitude(77.51);
        return r;
    }

    private CreateListingRequest createListingRequest(Property property) {
        CreateListingRequest r = new CreateListingRequest();
        r.setPropertyId(property.getId());
        r.setRent(new BigDecimal("21000"));
        r.setDeposit(new BigDecimal("100000"));
        r.setBhk(2);
        r.setBathrooms(2);
        r.setBuiltUpArea(new BigDecimal("1000"));
        r.setFloor(3);
        r.setFurnishingStatus(FurnishingStatus.SEMI_FURNISHED);
        r.setBachelorsAllowed(true);
        r.setParkingAvailable(true);
        r.setPetsAllowed(false);
        r.setAvailableFrom(LocalDate.now().plusDays(7));
        r.setDescription("ADMIN-created draft listing");
        return r;
    }

    /** Immutable projection of the persisted columns relevant to authorization/locking assertions. */
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
                    && Objects.equals(description, other.description);
        }
        @Override
        public int hashCode() {
            return Objects.hash(status, version, rent, description);
        }
        @Override
        public String toString() {
            return "RowSnapshot{status=" + status + ", version=" + version
                    + ", rent=" + rent + ", description=" + description + "}";
        }
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

    private UUID propertyOwnerId(UUID propertyId) {
        return jdbcTemplate.queryForObject(
                "SELECT owner_id FROM properties WHERE id = ?", UUID.class, propertyId);
    }

    private UUID listingPropertyOwnerId(UUID listingId) {
        return jdbcTemplate.queryForObject(
                "SELECT p.owner_id FROM listings l JOIN properties p ON p.id = l.property_id WHERE l.id = ?",
                UUID.class, listingId);
    }

    /** Full business-field edit carrying the CURRENT version, so only authorization/version/lifecycle can fail it. */
    private UpdateListingRequest validEditRequest(int currentVersion) {
        UpdateListingRequest r = new UpdateListingRequest();
        r.setRent(new BigDecimal("31313.00"));
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
        r.setDescription("ADMIN edited description");
        r.setVersion(currentVersion);
        return r;
    }

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

    private User newRenter() {
        String suffix = UUID.randomUUID().toString();
        User u = new User();
        u.setName("Renter " + suffix);
        u.setEmail("renter-" + suffix + "@example.com");
        u.setPasswordHash("hash");
        u.setRole(UserRole.RENTER);
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
        listing.setDescription("Admin authorization test listing");
        listing.setStatus(ListingStatus.DRAFT);
        listing.setCreatedAt(OffsetDateTime.now());
        listing.setUpdatedAt(OffsetDateTime.now());
        Listing saved = listingRepository.save(listing);
        createdListings.add(saved.getId());
        return saved.getId();
    }
}
