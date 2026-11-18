package com.veritas.property;

import com.veritas.listing.Listing;
import com.veritas.listing.ListingRepository;
import com.veritas.listing.ListingService;
import com.veritas.listing.ListingStatus;
import com.veritas.listing.dto.ListingResponse;
import com.veritas.property.dto.PropertySummaryResponse;
import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import com.veritas.verification.LocationVerificationStatus;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3B.4B query-performance integration test against a real PostgreSQL/PostGIS database provided
 * by Testcontainers (same isolated bootstrap as the POI/search integration suites: ephemeral container,
 * its own port/volume/credentials, Flyway applies all migrations, {@code ddl-auto=validate}, geocoding
 * disabled). Tagged {@code integration} so the default {@code ./mvnw test} never requires Docker while
 * {@code ./mvnw -Pintegration test} executes it.
 *
 * <p>Using Hibernate {@link Statistics} it proves, against a real database:
 * <ul>
 *   <li>a paged owner-properties read issues exactly ONE PropertyImage query (the grouped count) for any
 *       number of rows, and returns correct per-property image counts (including 0 for a property with
 *       no images);</li>
 *   <li>a paged owner-listings read does the same, and the {@code JOIN FETCH l.property} repository change
 *       keeps the total statement count bounded (no per-row property load), while pagination totals stay
 *       correct.</li>
 * </ul>
 *
 * <p>It deliberately does NOT claim every N+1 is gone: it measures and prints the total statement count so
 * any residual lazy-loading is visible rather than asserted away.</p>
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@DisplayName("Phase 3B.4B: owner pagination query performance (real PostGIS + Hibernate Statistics)")
class PropertyListingQueryPerformanceIntegrationTest {

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
    private PropertyService propertyService;
    @Autowired
    private ListingService listingService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PropertyRepository propertyRepository;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private PropertyImageRepository propertyImageRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private final List<UUID> createdImages = new ArrayList<>();
    private final List<UUID> createdListings = new ArrayList<>();
    private final List<UUID> createdProperties = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void enableStatistics() {
        Statistics stats = statistics();
        stats.setStatisticsEnabled(true);
    }

    @AfterEach
    void cleanUpCreatedRows() {
        // Removes only the rows this test inserted (by generated id); never truncates tables.
        propertyImageRepository.deleteAllById(createdImages);
        listingRepository.deleteAllById(createdListings);
        propertyRepository.deleteAllById(createdProperties);
        userRepository.deleteAllById(createdUsers);
        createdImages.clear();
        createdListings.clear();
        createdProperties.clear();
        createdUsers.clear();
    }

    /**
     * Clears statistics, runs the action and returns the number of prepared JDBC statements issued by
     * that call (i.e. the real SQL-statement count for one paginated read) together with its result.
     */
    private Result measure(Supplier<?> action) {
        Statistics stats = statistics();
        stats.clear();
        Object value = action.get();
        long statements = stats.getPrepareStatementCount();
        return new Result(statements, value);
    }

    private record Result(long statements, Object value) {
    }

    @Test
    @DisplayName("owner-properties page: single grouped image-count query and correct counts (incl. zero)")
    void ownerPropertiesBatchesImageCounts() {
        User owner = newOwner();
        Property a = newProperty(owner, 12.97, 77.59);
        Property b = newProperty(owner, 12.98, 77.60); // no images
        Property c = newProperty(owner, 12.99, 77.61);
        newImage(a);
        newImage(a);
        newImage(c);
        newImage(c);
        newImage(c);
        newImage(c);
        newImage(c); // c has 5

        Result r = measure(() -> propertyService.getOwnerProperties(owner.getId(), PageRequest.of(0, 20)));

        @SuppressWarnings("unchecked")
        Page<PropertySummaryResponse> page = (Page<PropertySummaryResponse>) r.value();
        Map<UUID, Integer> counts = page.getContent().stream()
                .collect(Collectors.toMap(PropertySummaryResponse::getId, PropertySummaryResponse::getImageCount));
        assertEquals(2, counts.get(a.getId()), "property a has 2 images");
        assertEquals(0, counts.get(b.getId()), "property b has no images -> 0");
        assertEquals(5, counts.get(c.getId()), "property c has 5 images");
        assertEquals(3, page.getContent().size());

        // content + count + a single grouped image count == ~3 statements for the whole page. If the
        // per-row COUNT N+1 were still present, three properties would add three statements (>= 5), so
        // this bound directly proves the image-count batching against the real database.
        assertTrue(r.statements() < 5,
                "expected a bounded statement count for the page (batched image count), was " + r.statements());
        System.out.println("[Perf][getOwnerProperties] N=3 statements=" + r.statements());
    }

    @Test
    @DisplayName("owner-listings page: single grouped image-count query, no per-row property load, correct counts")
    void ownerListingsBatchAndFetchJoin() {
        User owner = newOwner();
        Property a = newProperty(owner, 12.97, 77.59);
        Property b = newProperty(owner, 12.98, 77.60); // no images
        newImage(a);
        newImage(a);
        newImage(a); // a has 3

        // Four listings across two properties, all published so they share one owner page.
        newListing(a, ListingStatus.PUBLISHED);
        newListing(a, ListingStatus.UNAVAILABLE);
        newListing(b, ListingStatus.PUBLISHED);
        newListing(b, ListingStatus.DRAFT);

        Result r = measure(() -> listingService.getOwnerListings(owner.getId(), null, PageRequest.of(0, 20)));

        @SuppressWarnings("unchecked")
        Page<ListingResponse> page = (Page<ListingResponse>) r.value();
        assertEquals(4, page.getTotalElements(), "all four listings belong to the owner");
        assertEquals(4, page.getContent().size());
        Map<UUID, Integer> countsByProperty = page.getContent().stream()
                .collect(Collectors.toMap(
                        lr -> lr.getProperty().getId(),
                        lr -> lr.getProperty().getImageCount(),
                        (x, y) -> x));
        assertEquals(3, countsByProperty.get(a.getId()));
        assertEquals(0, countsByProperty.get(b.getId()));

        // With JOIN FETCH property the page is content + count + a single grouped image count (~3).
        // A per-row property load would add one statement per listing (4 here) and a per-row image count
        // would add more, so this bound proves both the fetch join and the batching against real PostGIS.
        assertTrue(r.statements() < 6,
                "expected a bounded statement count (property fetch-joined, image counts batched), was "
                        + r.statements());
        System.out.println("[Perf][getOwnerListings] N=4 statements=" + r.statements());
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

    private void newImage(Property property) {
        PropertyImage image = new PropertyImage();
        image.setProperty(property);
        image.setStorageKey("internal/key-" + UUID.randomUUID() + ".jpg");
        image.setOriginalFilename("photo.jpg");
        image.setContentType("image/jpeg");
        image.setFileSizeBytes(2048L);
        image.setDisplayOrder(0);
        image.setCreatedAt(OffsetDateTime.now());
        image = propertyImageRepository.save(image);
        createdImages.add(image.getId());
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
}
