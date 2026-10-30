package com.veritas.search;

import com.veritas.listing.Listing;
import com.veritas.listing.ListingRepository;
import com.veritas.listing.ListingStatus;
import com.veritas.property.FurnishingStatus;
import com.veritas.property.Property;
import com.veritas.property.PropertyImage;
import com.veritas.property.PropertyImageRepository;
import com.veritas.property.PropertyRepository;
import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import com.veritas.verification.LocationVerificationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end discovery test against a real PostgreSQL/PostGIS database.
 *
 * <p><b>Deliberately {@code Disabled} by default.</b> The project currently has no provisioned
 * PostGIS test database and the task forbids altering Docker/credentials or substituting an
 * in-memory database for spatial verification. Enabling this test requires pointing the Spring
 * context at an isolated PostGIS instance (for example a Testcontainers
 * {@code postgis/postgis} container with the existing Flyway migrations applied). Until such a
 * database has actually executed these assertions, PostGIS behaviour is NOT claimed to be verified.
 * </p>
 *
 * <p>When run against real PostGIS it exercises: the published-only restriction, business filters,
 * {@code ST_DWithin} radius filtering, nearest-first KNN ordering, matching pagination counts, the
 * single-query image count (no N+1), and the privacy-safe projection.</p>
 */
@SpringBootTest
@Disabled("Requires a dedicated PostGIS database (e.g. Testcontainers). Not run to avoid falsely "
        + "claiming spatial verification against a substituted in-memory database.")
class PropertySearchPostGisIntegrationTest {

    @Autowired
    private PropertySearchRepository propertySearchRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PropertyRepository propertyRepository;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private PropertyImageRepository propertyImageRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private final List<UUID> createdListings = new ArrayList<>();
    private final List<UUID> createdImages = new ArrayList<>();
    private final List<UUID> createdProperties = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUpCreatedRows() {
        // Removes only the specific rows this test inserted (by generated id); never truncates tables.
        listingRepository.deleteAllById(createdListings);
        propertyImageRepository.deleteAllById(createdImages);
        propertyRepository.deleteAllById(createdProperties);
        userRepository.deleteAllById(createdUsers);
        createdListings.clear();
        createdImages.clear();
        createdProperties.clear();
        createdUsers.clear();
    }

    @Test
    @DisplayName("only published listings are returned; other statuses never appear")
    void publishedOnly() {
        Property published = newProperty(12.97, 77.59);
        Property draft = newProperty(12.98, 77.60);
        Property rented = newProperty(12.99, 77.61);
        newListing(published, ListingStatus.PUBLISHED, new BigDecimal("20000"), 2);
        newListing(draft, ListingStatus.DRAFT, new BigDecimal("20000"), 2);
        newListing(rented, ListingStatus.RENTED, new BigDecimal("20000"), 2);

        Page<PropertySearchRow> page = propertySearchRepository.search(
                empty(), PageRequest.of(0, 20));

        assertTrue(page.getContent().stream()
                .allMatch(r -> r.getPropertyId().equals(published.getId())));
        assertEquals(1, page.getTotalElements());
    }

    @Test
    @DisplayName("radius filtering keeps only properties within the circle, ordered nearest first")
    void radiusAndNearestFirst() {
        Property near = newProperty(12.9700, 77.5900);
        Property far = newProperty(13.0600, 77.5900);
        Property outside = newProperty(13.5000, 77.9000);
        newListing(near, ListingStatus.PUBLISHED, new BigDecimal("20000"), 2);
        newListing(far, ListingStatus.PUBLISHED, new BigDecimal("20000"), 2);
        newListing(outside, ListingStatus.PUBLISHED, new BigDecimal("20000"), 2);

        PropertySearchCriteria within10km = new PropertySearchCriteria(
                null, null, null, null, null, null, null, null, null, null,
                12.9700, 77.5900, 10000.0, null, null);

        Page<PropertySearchRow> page = propertySearchRepository.search(within10km, PageRequest.of(0, 20));
        List<UUID> ids = page.getContent().stream().map(PropertySearchRow::getPropertyId).toList();

        assertTrue(ids.contains(near.getId()));
        assertTrue(ids.contains(far.getId()));
        assertFalse(ids.contains(outside.getId()));
        // Nearest-first: the closest property must lead.
        assertEquals(near.getId(), ids.get(0));
        // Distance projected for each result.
        assertNotNullDistance(page.getContent());
    }

    @Test
    @DisplayName("pagination returns a bounded page with an accurate total count")
    void paginationAndCount() {
        for (int i = 0; i < 5; i++) {
            Property p = newProperty(12.9 + i * 0.001, 77.59);
            newListing(p, ListingStatus.PUBLISHED, new BigDecimal("20000"), 2);
        }

        Page<PropertySearchRow> page = propertySearchRepository.search(
                empty(), PageRequest.of(1, 2));

        assertEquals(2, page.getContent().size());
        assertEquals(5, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
    }

    @Test
    @DisplayName("image count is projected without a per-result query")
    void imageCountProjectedWithoutNPlusOne() {
        Property p = newProperty(12.97, 77.59);
        newListing(p, ListingStatus.PUBLISHED, new BigDecimal("20000"), 2);
        newImage(p);
        newImage(p);

        Page<PropertySearchRow> page = propertySearchRepository.search(empty(), PageRequest.of(0, 20));
        assertEquals(2, page.getContent().get(0).getImageCount());
    }

    private void assertNotNullDistance(List<PropertySearchRow> rows) {
        assertTrue(rows.stream().allMatch(r -> r.getDistanceMeters() != null));
    }

    private PropertySearchCriteria empty() {
        return new PropertySearchCriteria(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
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

    private void newListing(Property property, ListingStatus status, BigDecimal rent, int bhk) {
        Listing listing = new Listing();
        listing.setProperty(property);
        listing.setRent(rent);
        listing.setDeposit(new BigDecimal("100000"));
        listing.setBhk(bhk);
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
}
