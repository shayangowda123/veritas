package com.veritas.user.dto;

import java.util.UUID;

public record RegisterUserResponse(
        UUID userId,
        String name,
        String email,
        String role,
        boolean verified,
        String message
) {
}