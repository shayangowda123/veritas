package com.veritas.property;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PropertyImageRepository extends JpaRepository<PropertyImage, UUID> {
}