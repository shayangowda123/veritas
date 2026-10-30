package com.veritas.poi.discovery;

import com.veritas.listing.Listing;
import com.veritas.listing.ListingRepository;
import com.veritas.listing.ListingStatus;
import com.veritas.poi.Poi;
import com.veritas.poi.PoiRepository;
import com.veritas.poi.PoiType;
import com.veritas.poi.discovery.dto.PoiNearbyRequest;
import com.veritas.poi.discovery.dto.PoiNearbyResult;
import com.veritas.poi.discovery.dto.PoiNearbySummary;
import com.veritas.property.FurnishingStatus;
import com.veritas.property.Property;
import com.veritas.property.PropertyRepository;
import com.veritas.search.dto.PagedResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end nearby-places integration test against a real PostgreSQL + PostGIS database provided by
 * Testcontainers. It is tagged {@code integration} so the default {@code ./mvnw test} run (which
 * excludes that group) never requires Docker, while {@code ./mvnw -Pintegration test} runs it against
 * an isolated, ephemeral PostGIS container.
 *
 * <p><b>Isolation guarantees.</b> The container is fully independent of the development database: it
 * uses its own random host port, its own ephemeral volume and its own credentials, and Testcontainers
 * removes it after the run. It never connects to the {@code veritas-postgres} dev container, never
 * reuses the {@code veritas_postgres_data} volume and never touches Docker Compose, ports, credentials,
 * migrations or application configuration. {@code @DynamicPropertySource} re-points only the Spring
 * datasource at the container; Flyway applies V1-V5 to the empty database and Hibernate runs with
 * {@code ddl-auto=validate}. The geocoding provider is disabled so no external network service is
 * required.</p>
 *
 * <p>Each test seeds only the rows it needs and deletes exactly those rows by generated id in
 * {@code @AfterEach}; no table is ever truncated.</p>
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@DisplayName("Phase 2C: Nearby POI discovery against isolated real PostGIS (Testcontainers)")
class PoiNearbyPostGisIntegrationTest {

    /** Shared ephemeral PostGIS container, matched to the production image so spatial semantics are identical. */
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
        // No external services and no reliance on host env vars for the JWT beans.
        registry.add("veritas.geocoding.enabled", () -> "false");
        registry.add("security.jwt.secret-base64",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci12ZXJpdGFzLXJlbnRhbC1hcHAtMjU2LWJpdC1taW5pbXVtIQ==");
        registry.add("security.jwt.expiration-ms", () -> "3600000");
    }

    // Search centre: Indiranagar, Bengaluru. Longitude = X, latitude = Y.
    private static final double CENTRE_LAT = 12.9700;
    private static final double CENTRE_LON = 77.5900;

    /**
     * Mirrors PostgreSQL {@code uuid} ASC ordering: unsigned, byte-wise comparison of the 128-bit value
     * (the 8 most-significant bytes, then the 8 least-significant bytes). This is deliberately different
     * from {@link java.util.UUID#compareTo(Object)}, which compares the two 64-bit halves as <em>signed</em>
     * longs and therefore disagrees with the database whenever a UUID's high bit is set. Test-only helper
     * used to compute the expected {@code p.id ASC} tie-break order; it changes no production behaviour.
     */
    private static final Comparator<UUID> POSTGRES_UUID_ASC = (left, right) -> {
        int cmp = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return cmp != 0 ? cmp : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };

    @Autowired
    private PoiNearbyRepository poiNearbyRepository;
    @Autowired
    private PoiNearbyService poiNearbyService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PropertyRepository propertyRepository;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private PoiRepository poiRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private final List<UUID> createdPois = new ArrayList<>();
    private final List<UUID> createdListings = new ArrayList<>();
    private final List<UUID> createdProperties = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUpCreatedRows() {
        // Removes only the specific rows this test inserted (by generated id); never truncates tables.
        poiRepository.deleteAllById(createdPois);
        listingRepository.deleteAllById(createdListings);
        propertyRepository.deleteAllById(createdProperties);
        userRepository.deleteAllById(createdUsers);
        createdPois.clear();
        createdListings.clear();
        createdProperties.clear();
        createdUsers.clear();
    }

    // ---- 1. Published property centre lookup -------------------------------------------------

    @Test
    @DisplayName("1. published listing resolves the centre through the Java repository on real PostGIS")
    void publishedPropertyCentreLookup() {
        Property published = newPublishedProperty(CENTRE_LAT, CENTRE_LON);

        Optional<PoiNearbyCentre> centre =
                poiNearbyRepository.findPublishedPropertyCentre(published.getId());

        assertTrue(centre.isPresent(), "a published listing must resolve a server-side centre");
        // This exercises ST_Y/ST_X(p.location::geometry) against real PostGIS geography data.
        assertEquals(CENTRE_LAT, centre.get().latitude(), 1e-6);
        assertEquals(CENTRE_LON, centre.get().longitude(), 1e-6);
    }

    // ---- 2. Unpublished property returns no centre -------------------------------------------

    @Test
    @DisplayName("2. a non-published listing resolves no centre")
    void unpublishedPropertyReturnsNoCentre() {
        Property draft = newProperty(CENTRE_LAT, CENTRE_LON);
        newListing(draft, ListingStatus.DRAFT);

        assertFalse(poiNearbyRepository.findPublishedPropertyCentre(draft.getId()).isPresent());
    }

    // ---- 3. Missing property returns no centre ------------------------------------------------

    @Test
    @DisplayName("3. an unknown property id resolves no centre")
    void missingPropertyReturnsNoCentre() {
        assertFalse(poiNearbyRepository.findPublishedPropertyCentre(UUID.randomUUID()).isPresent());
    }

    // ---- 4 & 5. Radius inclusion / exclusion ---------------------------------------------------

    @Test
    @DisplayName("4. POIs inside the radius are returned")
    void poisInsideRadiusAreReturned() {
        Property published = newPublishedProperty(CENTRE_LAT, CENTRE_LON);
        Poi aSchool = newPoi("A School", PoiType.SCHOOL, 12.9705, CENTRE_LON);   // ~56 m
        Poi smallGrocery = newPoi("Small Grocery", PoiType.GROCERY, 12.9720, CENTRE_LON); // ~223 m
        Poi cityPark = newPoi("City Park", PoiType.PARK, CENTRE_LAT, 77.6010);   // ~1193 m

        Page<PoiNearbyRow> page = poiNearbyRepository.search(
                criteria(2000.0, List.of(), "distance"), PageRequest.of(0, 20));

        List<UUID> ids = idsOf(page);
        assertTrue(ids.contains(aSchool.getId()));
        assertTrue(ids.contains(smallGrocery.getId()));
        assertTrue(ids.contains(cityPark.getId()));
    }

    @Test
    @DisplayName("5. POIs outside the radius are excluded")
    void poisOutsideRadiusAreExcluded() {
        Property published = newPublishedProperty(CENTRE_LAT, CENTRE_LON);
        Poi inside = newPoi("Inside School", PoiType.SCHOOL, 12.9705, CENTRE_LON); // ~56 m
        Poi outside = newPoi("Far School", PoiType.SCHOOL, 13.0500, CENTRE_LON);   // ~8.9 km

        Page<PoiNearbyRow> page = poiNearbyRepository.search(
                criteria(2000.0, List.of(), "distance"), PageRequest.of(0, 20));

        List<UUID> ids = idsOf(page);
        assertTrue(ids.contains(inside.getId()));
        assertFalse(ids.contains(outside.getId()), "ST_DWithin must drop out-of-circle POIs");
        assertEquals(1, page.getTotalElements());
    }

    // ---- 6. Category filtering (single) -------------------------------------------------------

    @Test
    @DisplayName("6. a single category filters to exactly that type")
    void categoryFilterSingle() {
        newPublishedProperty(CENTRE_LAT, CENTRE_LON);
        Poi school = newPoi("A School", PoiType.SCHOOL, 12.9705, CENTRE_LON);
        newPoi("Small Grocery", PoiType.GROCERY, 12.9720, CENTRE_LON);
        newPoi("Green Metro", PoiType.METRO, 12.9740, CENTRE_LON);

        Page<PoiNearbyRow> page = poiNearbyRepository.search(
                criteria(2000.0, List.of("SCHOOL"), "distance"), PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals(school.getId(), page.getContent().get(0).id());
        assertTrue(page.getContent().stream().allMatch(r -> r.type() == PoiType.SCHOOL));
    }

    // ---- 7. Multiple categories ---------------------------------------------------------------

    @Test
    @DisplayName("7. multiple categories bind and expand into indexed placeholders")
    void categoryFilterMultiple() {
        newPublishedProperty(CENTRE_LAT, CENTRE_LON);
        Poi school = newPoi("A School", PoiType.SCHOOL, 12.9705, CENTRE_LON);
        Poi metro = newPoi("Green Metro", PoiType.METRO, 12.9740, CENTRE_LON);
        newPoi("City Park", PoiType.PARK, CENTRE_LAT, 77.6010);

        Page<PoiNearbyRow> page = poiNearbyRepository.search(
                criteria(2000.0, List.of("SCHOOL", "METRO"), "name"), PageRequest.of(0, 20));

        assertEquals(2, page.getTotalElements());
        List<UUID> ids = idsOf(page);
        assertTrue(ids.contains(school.getId()));
        assertTrue(ids.contains(metro.getId()));
        assertTrue(page.getContent().stream()
                .allMatch(r -> r.type() == PoiType.SCHOOL || r.type() == PoiType.METRO));
    }

    // ---- 8. Pagination ------------------------------------------------------------------------

    @Test
    @DisplayName("8. bounded pagination returns disjoint pages with an accurate total count")
    void pagination() {
        newPublishedProperty(CENTRE_LAT, CENTRE_LON);
        newPoi("A School", PoiType.SCHOOL, 12.9705, CENTRE_LON);          // 56
        newPoi("Small Grocery", PoiType.GROCERY, 12.9720, CENTRE_LON);    // 223
        newPoi("Green Metro", PoiType.METRO, 12.9740, CENTRE_LON);        // 445
        newPoi("Big School", PoiType.SCHOOL, 12.9790, CENTRE_LON);        // 1002
        newPoi("City Park", PoiType.PARK, CENTRE_LAT, 77.6010);           // 1193
        newPoi("Far School", PoiType.SCHOOL, 13.0500, CENTRE_LON);        // excluded

        Page<PoiNearbyRow> p0 = poiNearbyRepository.search(
                criteria(2000.0, List.of(), "distance"), PageRequest.of(0, 2));
        Page<PoiNearbyRow> p1 = poiNearbyRepository.search(
                criteria(2000.0, List.of(), "distance"), PageRequest.of(1, 2));
        Page<PoiNearbyRow> p2 = poiNearbyRepository.search(
                criteria(2000.0, List.of(), "distance"), PageRequest.of(2, 2));

        assertEquals(2, p0.getContent().size());
        assertEquals(2, p1.getContent().size());
        assertEquals(1, p2.getContent().size());
        assertEquals(5, p0.getTotalElements());
        assertEquals(3, p0.getTotalPages());
        // Pages are disjoint: no id repeats across pages.
        List<UUID> all = new ArrayList<>();
        all.addAll(idsOf(p0));
        all.addAll(idsOf(p1));
        all.addAll(idsOf(p2));
        assertEquals(5, all.stream().distinct().count());
    }

    // ---- 9. Distance sorting ------------------------------------------------------------------

    @Test
    @DisplayName("9. distance sorting returns nearest-first")
    void distanceSorting() {
        newPublishedProperty(CENTRE_LAT, CENTRE_LON);
        newPoi("City Park", PoiType.PARK, CENTRE_LAT, 77.6010);        // 1193
        newPoi("A School", PoiType.SCHOOL, 12.9705, CENTRE_LON);        // 56
        newPoi("Big School", PoiType.SCHOOL, 12.9790, CENTRE_LON);      // 1002
        newPoi("Green Metro", PoiType.METRO, 12.9740, CENTRE_LON);      // 445
        newPoi("Small Grocery", PoiType.GROCERY, 12.9720, CENTRE_LON);  // 223

        Page<PoiNearbyRow> page = poiNearbyRepository.search(
                criteria(2000.0, List.of(), "distance"), PageRequest.of(0, 20));

        List<String> names = namesOf(page);
        assertEquals(
                List.of("A School", "Small Grocery", "Green Metro", "Big School", "City Park"),
                names);
    }

    // ---- 10. Name sorting ---------------------------------------------------------------------

    @Test
    @DisplayName("10. name sorting returns alphabetical ascending")
    void nameSorting() {
        newPublishedProperty(CENTRE_LAT, CENTRE_LON);
        newPoi("Small Grocery", PoiType.GROCERY, 12.9720, CENTRE_LON);
        newPoi("A School", PoiType.SCHOOL, 12.9705, CENTRE_LON);
        newPoi("City Park", PoiType.PARK, CENTRE_LAT, 77.6010);
        newPoi("Big School", PoiType.SCHOOL, 12.9790, CENTRE_LON);
        newPoi("Green Metro", PoiType.METRO, 12.9740, CENTRE_LON);

        Page<PoiNearbyRow> page = poiNearbyRepository.search(
                criteria(2000.0, List.of(), "name"), PageRequest.of(0, 20));

        assertEquals(
                List.of("A School", "Big School", "City Park", "Green Metro", "Small Grocery"),
                namesOf(page));
    }

    // ---- 11. Deterministic tie-breaking -------------------------------------------------------

    @Test
    @DisplayName("11. equal-distance rows fall back to PostgreSQL p.id ASC order (unsigned byte-wise, not Java UUID.compareTo)")
    void deterministicTieBreaking() {
        newPublishedProperty(CENTRE_LAT, CENTRE_LON);
        // Symmetric offsets => equal spheroidal distance; identical names for both rows.
        Poi east = newPoi("Tied Place", PoiType.SCHOOL, CENTRE_LAT, 77.5910);  // ~108 m east
        Poi west = newPoi("Tied Place", PoiType.SCHOOL, CENTRE_LAT, 77.5890);  // ~108 m west

        Page<PoiNearbyRow> first = poiNearbyRepository.search(
                criteria(2000.0, List.of(), "distance"), PageRequest.of(0, 20));
        Page<PoiNearbyRow> second = poiNearbyRepository.search(
                criteria(2000.0, List.of(), "distance"), PageRequest.of(0, 20));

        List<PoiNearbyRow> rows = first.getContent();
        List<UUID> order1 = idsOf(first);
        List<UUID> order2 = idsOf(second);

        // (4) Both expected POIs are present.
        assertEquals(2, order1.size());
        assertTrue(order1.containsAll(List.of(east.getId(), west.getId())),
                "both symmetric POIs must be returned");

        // (1) The two POIs sit at an equal spatial distance from the centre, so distance cannot decide
        //     the order and the query genuinely exercises the tie-breaker.
        assertEquals(rows.get(0).distanceMeters(), rows.get(1).distanceMeters(), 1e-6,
                "tied rows must have equal distance; otherwise this does not test tie-breaking");

        // (3) Repeated identical queries return the same id sequence (stable, never arbitrary).
        assertEquals(order1, order2);

        // (2) The database breaks the distance tie with p.id ASC using PostgreSQL's unsigned byte-wise
        //     uuid ordering, NOT Java's signed-long UUID.compareTo.
        List<UUID> expected = new ArrayList<>(List.of(east.getId(), west.getId()));
        expected.sort(POSTGRES_UUID_ASC);
        assertEquals(expected, order1);
    }

    // ---- 12. Privacy-safe response projection -------------------------------------------------

    @Test
    @DisplayName("12. the public response exposes only safe fields, never coordinates or metadata")
    void privacySafeProjection() {
        Property published = newPublishedProperty(CENTRE_LAT, CENTRE_LON);
        newPoi("A School", PoiType.SCHOOL, 12.9705, CENTRE_LON);

        PoiNearbyRequest request = new PoiNearbyRequest();
        request.setRadiusInMeters(2000.0);
        request.setSortBy("distance");
        request.setPage(0);
        request.setSize(20);

        PagedResponse<PoiNearbyResult> response = poiNearbyService.search(published.getId(), request);

        assertEquals(1, response.content().size());
        PoiNearbyResult result = response.content().get(0);
        assertNotNull(result.distanceMeters());

        List<String> summaryFields = Arrays.stream(PoiNearbySummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("id", "name", "category", "city", "locality"), summaryFields);
        for (String forbidden : List.of(
                "latitude", "longitude", "location", "address",
                "source", "externalId", "sourceUrl", "licence", "createdAt", "updatedAt", "lastSeenAt")) {
            assertFalse(summaryFields.contains(forbidden),
                    "public summary must not expose " + forbidden);
        }
    }

    // ---- 13. KNN (spherical) vs spheroidal distance trade-off ---------------------------------

    /**
     * The distance ORDER BY uses the geography KNN operator {@code <->} (index-accelerated, spherical
     * model) while the projected distance column uses {@code ST_Distance} (spheroidal, ellipsoidal
     * model). These two PostGIS models differ by roughly 0.2% (~3 m at 1.5 km, measured). This test
     * documents the accepted trade-off: for well-separated POIs the {@code <->} ordering agrees with
     * the reported spheroidal distances (monotonic within a small tolerance), and the id tie-breaker
     * keeps near-tie cases deterministic. Tolerances rather than exact equality are used so the test
     * locks in the intended behaviour without falsely asserting identical distance models.
     */
    @Test
    @DisplayName("13. KNN ordering is consistent with spheroidal distance within tolerance")
    void knnVsSpheroidalTradeOff() {
        newPublishedProperty(CENTRE_LAT, CENTRE_LON);
        newPoi("A School", PoiType.SCHOOL, 12.9705, CENTRE_LON);        // ~56 m
        newPoi("Green Metro", PoiType.METRO, 12.9740, CENTRE_LON);      // ~445 m
        newPoi("Big School", PoiType.SCHOOL, 12.9790, CENTRE_LON);      // ~1002 m
        newPoi("City Park", PoiType.PARK, CENTRE_LAT, 77.6010);         // ~1193 m

        Page<PoiNearbyRow> page = poiNearbyRepository.search(
                criteria(2000.0, List.of(), "distance"), PageRequest.of(0, 20));

        List<PoiNearbyRow> rows = page.getContent();
        assertEquals(4, rows.size());
        // Nearest-first by the KNN operator leads with the closest POI.
        assertEquals("A School", rows.get(0).name());

        // Reported (spheroidal ST_Distance) metres are within realistic bands for the seeded offsets.
        assertCloseEnough(rows.get(0).distanceMeters(), 55.6, 15.0);   // A School
        assertCloseEnough(rows.get(1).distanceMeters(), 445.3, 25.0);  // Green Metro
        assertCloseEnough(rows.get(2).distanceMeters(), 1001.9, 40.0); // Big School
        assertCloseEnough(rows.get(3).distanceMeters(), 1193.2, 45.0); // City Park

        // The KNN ordering equals ordering by the reported spheroidal distance (monotonic within 1 m).
        for (int i = 0; i < rows.size() - 1; i++) {
            double current = rows.get(i).distanceMeters();
            double next = rows.get(i + 1).distanceMeters();
            assertTrue(current <= next + 1.0,
                    "spheroidal distance must be non-decreasing along the KNN order at index " + i);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private PoiNearbyCriteria criteria(double radius, List<String> categories, String sortBy) {
        return new PoiNearbyCriteria(CENTRE_LAT, CENTRE_LON, radius, categories, sortBy);
    }

    private static List<UUID> idsOf(Page<PoiNearbyRow> page) {
        return page.getContent().stream().map(PoiNearbyRow::id).toList();
    }

    private static List<String> namesOf(Page<PoiNearbyRow> page) {
        return page.getContent().stream().map(PoiNearbyRow::name).toList();
    }

    private static void assertCloseEnough(double actual, double expected, double tolerance) {
        assertTrue(Math.abs(actual - expected) <= tolerance,
                "expected ~" + expected + "m (+/-" + tolerance + ") but was " + actual + "m");
    }

    private Property newPublishedProperty(double lat, double lon) {
        Property property = newProperty(lat, lon);
        newListing(property, ListingStatus.PUBLISHED);
        return property;
    }

    private Property newProperty(double lat, double lon) {
        User owner = new User();
        String suffix = UUID.randomUUID().toString();
        owner.setName("Owner " + suffix);
        owner.setEmail("owner-" + suffix + "@example.com");
        owner.setPasswordHash("hash");
        owner.setRole(UserRole.OWNER);
        owner.setVerified(true);
        owner.setCreatedAt(OffsetDateTime.now());
        owner.setUpdatedAt(OffsetDateTime.now());
        owner = userRepository.save(owner);
        createdUsers.add(owner.getId());

        Property property = new Property();
        property.setOwner(owner);
        property.setPropertyType("APARTMENT");
        property.setAddress("Secret Address " + UUID.randomUUID());
        property.setLocality("Indiranagar");
        property.setCity("Bengaluru");
        property.setLocation(geometryFactory.createPoint(new Coordinate(lon, lat)));
        property.setLocationVerificationStatus(LocationVerificationStatus.VERIFIED);
        property.setCreatedAt(OffsetDateTime.now());
        property.setUpdatedAt(OffsetDateTime.now());
        property = propertyRepository.save(property);
        createdProperties.add(property.getId());
        return property;
    }

    private void newListing(Property property, ListingStatus status) {
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
        listing.setStatus(status);
        listing.setCreatedAt(OffsetDateTime.now());
        listing.setUpdatedAt(OffsetDateTime.now());
        listing = listingRepository.save(listing);
        createdListings.add(listing.getId());
    }

    private Poi newPoi(String name, PoiType type, double lat, double lon) {
        Poi poi = new Poi();
        poi.setName(name);
        poi.setType(type);
        poi.setLocation(geometryFactory.createPoint(new Coordinate(lon, lat)));
        poi.setCity("Bengaluru");
        poi.setLocality("Indiranagar");
        // Private fields intentionally populated to prove they are never projected into results.
        poi.setAddress("Private Address " + UUID.randomUUID());
        poi.setSource("manual");
        poi.setExternalId("ext-" + UUID.randomUUID());
        poi.setSourceUrl("https://example.invalid/poi");
        poi.setLicence("internal");
        poi.setCreatedAt(OffsetDateTime.now());
        poi = poiRepository.save(poi);
        createdPois.add(poi.getId());
        return poi;
    }
}
