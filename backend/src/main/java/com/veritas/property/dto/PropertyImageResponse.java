package com.veritas.property.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PropertyImageResponse {

    private UUID id;
    private UUID propertyId;
    private String storageKey;
    private String originalFilename;
    private String contentType;
    private Long fileSizeBytes;
    private Integer displayOrder;
    private OffsetDateTime createdAt;
    private String contentUrl;

    public PropertyImageResponse(
            UUID id,
            UUID propertyId,
            String storageKey,
            String originalFilename,
            String contentType,
            Long fileSizeBytes,
            Integer displayOrder,
            OffsetDateTime createdAt
    ) {
        this(
                id,
                propertyId,
                storageKey,
                originalFilename,
                contentType,
                fileSizeBytes,
                displayOrder,
                createdAt,
                (propertyId != null && id != null) ? "/api/v1/properties/" + propertyId + "/images/" + id + "/content" : null
        );
    }
}
