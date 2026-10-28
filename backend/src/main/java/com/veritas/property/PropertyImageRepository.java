package com.veritas.property;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyImageRepository extends JpaRepository<PropertyImage, UUID> {

    List<PropertyImage> findByPropertyIdOrderByDisplayOrderAsc(UUID propertyId);

    Optional<PropertyImage> findByIdAndPropertyId(UUID id, UUID propertyId);

    long countByPropertyId(UUID propertyId);
}