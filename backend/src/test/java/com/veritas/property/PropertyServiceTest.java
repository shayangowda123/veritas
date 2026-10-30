package com.veritas.property;

import com.veritas.common.exception.ResourceNotFoundException;
import com.veritas.common.exception.UnauthorizedActionException;
import com.veritas.listing.ListingRepository;
import com.veritas.property.dto.CreatePropertyRequest;
import com.veritas.property.dto.PropertyResponse;
import com.veritas.property.dto.UpdatePropertyRequest;
import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import com.veritas.verification.LocationVerificationStatus;
import com.veritas.verification.geocoding.service.LocationConsistencyResult;
import com.veritas.verification.geocoding.service.LocationConsistencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PropertyServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private PropertyImageRepository propertyImageRepository;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LocationConsistencyService locationConsistencyService;

    @InjectMocks
    private PropertyService propertyService;

    private User owner;
    private User renter;
    private Property property;
    private UUID propertyId;
    private UUID ownerId;
    private UUID renterId;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        renterId = UUID.randomUUID();
        propertyId = UUID.randomUUID();

        owner = new User();
        owner.setId(ownerId);
        owner.setName("Property Owner");
        owner.setEmail("owner@example.com");
        owner.setRole(UserRole.OWNER);

        renter = new User();
        renter.setId(renterId);
        renter.setName("Renter");
        renter.setEmail("renter@example.com");
        renter.setRole(UserRole.RENTER);

        property = new Property();
        property.setId(propertyId);
        property.setOwner(owner);
        property.setPropertyType("APARTMENT");
        property.setAddress("123 Indiranagar, Bengaluru");
        property.setLocality("Indiranagar");
        property.setCity("Bengaluru");
        property.setLocation(geometryFactory.createPoint(new Coordinate(77.641154, 12.978369)));
        property.setLocationVerificationStatus(LocationVerificationStatus.VERIFIED);
        property.setVerifiedAt(OffsetDateTime.now());
        property.setVerificationMethod("SITE_AUDIT");
        property.setVerificationNotes("Physically checked");
        property.setCreatedAt(OffsetDateTime.now());
        property.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    @DisplayName("Should create property with valid owner and initial PENDING verification status even on MATCH")
    void shouldCreatePropertySuccessfully() {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(locationConsistencyService.checkConsistency(any(), any(), any(), any(), any()))
                .thenReturn(LocationConsistencyResult.match(45.0, null));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> {
            Property p = invocation.getArgument(0);
            p.setId(propertyId);
            return p;
        });

        PropertyResponse response = propertyService.createProperty(ownerId, request);

        assertNotNull(response);
        assertEquals("APARTMENT", response.getPropertyType());
        // CRITICAL INVARIANT: Automated match never sets VERIFIED status; it remains PENDING
        assertEquals(LocationVerificationStatus.PENDING, response.getLocationVerificationStatus());
        assertNull(response.getVerifiedAt(), "verifiedAt must remain null for automated consistency matches");
        assertEquals("AUTOMATED_GEOCODING", response.getVerificationMethod());
        assertTrue(response.getVerificationNotes().contains("MATCH"));
        assertEquals(12.978369, response.getLatitude(), 0.0001);
        assertEquals(77.641154, response.getLongitude(), 0.0001);
    }

    @Test
    @DisplayName("Should assign REVIEW_REQUIRED status when location consistency detects a MISMATCH")
    void shouldSetReviewRequiredOnLocationMismatch() {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(locationConsistencyService.checkConsistency(any(), any(), any(), any(), any()))
                .thenReturn(LocationConsistencyResult.mismatch(4500.0, null));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> {
            Property p = invocation.getArgument(0);
            p.setId(propertyId);
            return p;
        });

        PropertyResponse response = propertyService.createProperty(ownerId, request);

        assertNotNull(response);
        assertEquals(LocationVerificationStatus.REVIEW_REQUIRED, response.getLocationVerificationStatus());
        assertNull(response.getVerifiedAt());
        assertTrue(response.getVerificationNotes().contains("MISMATCH"));
    }

    @Test
    @DisplayName("Should fail safely and preserve reviewable PENDING state if geocoding throws an exception")
    void shouldFailSafelyWhenGeocodingFails() {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(locationConsistencyService.checkConsistency(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Simulated external connection failure"));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> {
            Property p = invocation.getArgument(0);
            p.setId(propertyId);
            return p;
        });

        // Property creation must not throw; it must fail safely preserving PENDING state
        PropertyResponse response = propertyService.createProperty(ownerId, request);

        assertNotNull(response);
        assertEquals(LocationVerificationStatus.PENDING, response.getLocationVerificationStatus());
        assertNull(response.getVerifiedAt());
        assertTrue(response.getVerificationNotes().contains("unexpected internal error"));
    }

    @Test
    @DisplayName("Should reject property creation if user is not OWNER or ADMIN")
    void shouldRejectPropertyCreationForRenter() {
        CreatePropertyRequest request = new CreatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(userRepository.findById(renterId)).thenReturn(Optional.of(renter));

        assertThrows(UnauthorizedActionException.class, () ->
                propertyService.createProperty(renterId, request)
        );
    }

    @Test
    @DisplayName("Updating property coordinates or address automatically resets verification to PENDING")
    void shouldResetVerificationOnLocationChange() {
        UpdatePropertyRequest request = new UpdatePropertyRequest(
                "APARTMENT",
                "456 New Road, Koramangala",
                "Koramangala",
                "Bengaluru",
                12.935242,
                77.624480
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PropertyResponse response = propertyService.updateProperty(propertyId, ownerId, request);

        assertEquals(LocationVerificationStatus.PENDING, response.getLocationVerificationStatus());
        assertNull(response.getVerifiedAt());
        assertNull(response.getVerificationMethod());
        assertNull(response.getVerificationNotes());
    }

    @Test
    @DisplayName("Owner A cannot update Owner B's property")
    void shouldRejectUpdateFromUnauthorizedOwner() {
        UUID otherOwnerId = UUID.randomUUID();
        UpdatePropertyRequest request = new UpdatePropertyRequest(
                "APARTMENT",
                "123 Indiranagar",
                "Indiranagar",
                "Bengaluru",
                12.978369,
                77.641154
        );

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));

        assertThrows(UnauthorizedActionException.class, () ->
                propertyService.updateProperty(propertyId, otherOwnerId, request)
        );
    }

    @Test
    @DisplayName("Non-owner cannot view private property without a published listing")
    void shouldDenyPrivatePropertyAccessToNonOwner() {
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(listingRepository.hasPublishedListing(propertyId)).thenReturn(false);

        assertThrows(UnauthorizedActionException.class, () ->
                propertyService.getPropertyDetails(propertyId, renterId, false)
        );
    }

    @Test
    @DisplayName("Non-owner browsing published listing receives sanitized property response omitting full address, coordinates and notes")
    void shouldAllowViewingPropertyWithPublishedListing() {
        PropertyImage img = new PropertyImage();
        img.setId(UUID.randomUUID());
        img.setProperty(property);
        img.setStorageKey("secret/path/img.jpg");
        img.setOriginalFilename("photo.jpg");
        img.setContentType("image/jpeg");
        img.setFileSizeBytes(5000L);
        img.setDisplayOrder(0);
        img.setCreatedAt(OffsetDateTime.now());

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(listingRepository.hasPublishedListing(propertyId)).thenReturn(true);
        when(propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)).thenReturn(List.of(img));

        // Renter browsing published listing
        PropertyResponse response = propertyService.getPropertyDetails(propertyId, renterId, false);

        assertNotNull(response);
        assertEquals(propertyId, response.getId());
        // PRIVACY HARDENING ASSERTIONS:
        assertNull(response.getAddress(), "Full street address must be omitted for non-owners");
        assertNull(response.getLatitude(), "Exact latitude must be omitted for non-owners");
        assertNull(response.getLongitude(), "Exact longitude must be omitted for non-owners");
        assertNull(response.getVerificationNotes(), "Internal verification notes must be omitted for non-owners");
        assertNull(response.getVerificationMethod(), "Internal verification method must be omitted for non-owners");
        assertNull(response.getVerifiedAt(), "Verification timestamp must be omitted for non-owners");
        // Safe general neighborhood attributes are preserved
        assertEquals("Indiranagar", response.getLocality());
        assertEquals("Bengaluru", response.getCity());
        assertEquals(LocationVerificationStatus.VERIFIED, response.getLocationVerificationStatus());
        // Images must omit internal storage keys
        assertEquals(1, response.getImages().size());
        assertNull(response.getImages().get(0).getStorageKey(), "Storage keys must be omitted for non-owners");
        assertNotNull(response.getImages().get(0).getContentUrl(), "Safe content URL must be provided");
    }

    @Test
    @DisplayName("Property owner retains full access to exact address, coordinates, and verification notes")
    void shouldProvideFullDetailsToAuthorizedOwner() {
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)).thenReturn(List.of());

        PropertyResponse response = propertyService.getPropertyDetails(propertyId, ownerId, false);

        assertNotNull(response);
        assertEquals("123 Indiranagar, Bengaluru", response.getAddress());
        assertEquals(12.978369, response.getLatitude(), 0.0001);
        assertEquals(77.641154, response.getLongitude(), 0.0001);
        assertEquals("SITE_AUDIT", response.getVerificationMethod());
        assertEquals("Physically checked", response.getVerificationNotes());
        assertNotNull(response.getVerifiedAt());
    }

    @Test
    @DisplayName("Administrator retains full access to property verification details")
    void shouldProvideFullDetailsToAdmin() {
        UUID adminId = UUID.randomUUID();
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)).thenReturn(List.of());

        PropertyResponse response = propertyService.getPropertyDetails(propertyId, adminId, true);

        assertNotNull(response);
        assertEquals("123 Indiranagar, Bengaluru", response.getAddress());
        assertEquals(12.978369, response.getLatitude(), 0.0001);
        assertEquals(77.641154, response.getLongitude(), 0.0001);
        assertEquals("SITE_AUDIT", response.getVerificationMethod());
        assertEquals("Physically checked", response.getVerificationNotes());
    }
}
