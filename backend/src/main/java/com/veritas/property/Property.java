package com.veritas.property;

import com.veritas.user.User;
import com.veritas.verification.LocationVerificationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "properties")
@Getter
@Setter
@NoArgsConstructor
public class Property {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "property_type", nullable = false, length = 50)
    private String propertyType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String address;

    @Column(length = 255)
    private String locality;

    @Column(length = 100)
    private String city;

    @Column(nullable = false, columnDefinition = "geography(Point,4326)")
    private Point location;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_verification_status", nullable = false, length = 30)
    private LocationVerificationStatus locationVerificationStatus = LocationVerificationStatus.PENDING;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "verification_notes", columnDefinition = "TEXT")
    private String verificationNotes;

    @Column(name = "verification_method", length = 50)
    private String verificationMethod;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Double getLatitude() {
        return location != null ? location.getY() : null;
    }

    public Double getLongitude() {
        return location != null ? location.getX() : null;
    }
}