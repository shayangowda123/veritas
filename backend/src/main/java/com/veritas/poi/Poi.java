package com.veritas.poi;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "pois")
@Getter
@Setter
@NoArgsConstructor
public class Poi {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PoiType type;

    @Column(nullable = false, columnDefinition = "geography(Point,4326)")
    private Point location;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}