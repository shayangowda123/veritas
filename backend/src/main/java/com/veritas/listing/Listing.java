package com.veritas.listing;

import com.veritas.property.FurnishingStatus;
import com.veritas.property.Property;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "listings")
@Getter
@Setter
@NoArgsConstructor
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal rent;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal deposit;

    @Column(nullable = false)
    private Integer bhk;

    @Column(nullable = false)
    private Integer bathrooms = 1;

    @Column(name = "built_up_area", nullable = false, precision = 10, scale = 2)
    private BigDecimal builtUpArea;

    @Column(nullable = false)
    private Integer floor;

    @Enumerated(EnumType.STRING)
    @Column(name = "furnishing_status", nullable = false, length = 30)
    private FurnishingStatus furnishingStatus = FurnishingStatus.UNFURNISHED;

    @Column(name = "bachelors_allowed", nullable = false)
    private boolean bachelorsAllowed;

    @Column(name = "parking_available", nullable = false)
    private boolean parkingAvailable;

    @Column(name = "pets_allowed", nullable = false)
    private boolean petsAllowed;

    @Column(name = "available_from", nullable = false)
    private LocalDate availableFrom;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ListingStatus status = ListingStatus.DRAFT;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}