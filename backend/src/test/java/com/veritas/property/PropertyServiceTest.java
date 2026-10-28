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
    @DisplayName("Should create property with valid owner and initial PENDING verification status")
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
        when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> {
            Property p = invocation.getArgument(0);
            p.setId(propertyId);
            return p;
        });

        PropertyResponse response = propertyService.createProperty(ownerId, request);

        assertNotNull(response);
        assertEquals("APARTMENT", response.getPropertyType());
        assertEquals(LocationVerificationStatus.PENDING, response.getLocationVerificationStatus());
        assertNull(response.getVerifiedAt());
        assertEquals(12.978369, response.getLatitude(), 0.0001);
        assertEquals(77.641154, response.getLongitude(), 0.0001);
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
    @DisplayName("Non-owner can view property details if it has an active published listing")
    void shouldAllowViewingPropertyWithPublishedListing() {
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(listingRepository.hasPublishedListing(propertyId)).thenReturn(true);
        when(propertyImageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)).thenReturn(List.of());

        PropertyResponse response = propertyService.getPropertyDetails(propertyId, renterId, false);

        assertNotNull(response);
        assertEquals(propertyId, response.getId());
    }
}
