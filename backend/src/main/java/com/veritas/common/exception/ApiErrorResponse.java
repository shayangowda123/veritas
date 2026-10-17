package com.veritas.common.exception;

import java.time.OffsetDateTime;
import java.util.Map;

public record ApiErrorResponse(
        int status,
        String message,
        OffsetDateTime timestamp,
        Map<String, String> errors
) {
}