package com.veritas.common.exception;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.BindException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.veritas.common.exception.InvalidOtpException;
import com.veritas.common.exception.OtpAlreadyUsedException;
import com.veritas.common.exception.OtpAttemptsExceededException;
import com.veritas.common.exception.OtpExpiredException;
import com.veritas.common.exception.OtpNotFoundException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.time.OffsetDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailAlreadyExists(
            EmailAlreadyExistsException ex
    ) {

        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }

    @ExceptionHandler(PasswordMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handlePasswordMismatch(
            PasswordMismatchException ex
    ) {

        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    @ExceptionHandler(InvalidUserRoleException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidUserRole(
            InvalidUserRoleException ex
    ) {

        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex
    ) {

        Map<String, String> errors = new LinkedHashMap<>();

        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        errors.put(error.getField(), error.getDefaultMessage())
                );

        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                OffsetDateTime.now(),
                errors
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Handles validation and type-conversion failures raised for {@code @ModelAttribute} query
     * bindings (for example public property discovery). Query parameters are not request-body
     * arguments, so binding violations surface as {@link BindException} rather than
     * {@link MethodArgumentNotValidException}. Field errors (including invalid enum, date or numeric
     * values) are reported through the same field-to-message {@code errors} contract.
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> handleBindErrors(
            BindException ex
    ) {

        Map<String, String> errors = new LinkedHashMap<>();

        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        errors.putIfAbsent(error.getField(), error.getDefaultMessage())
                );

        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                OffsetDateTime.now(),
                errors
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Handles request-parameter and path-variable type-conversion failures (for example an invalid
     * enum value such as {@code ?status=BOGUS} or a malformed UUID path variable). These are raised
     * during argument resolution rather than bean validation, so without this handler they would
     * bypass the unified {@link ApiErrorResponse} envelope and fall through to Spring's default
     * resolver. Only the offending parameter name and a safe, generic message are returned: no
     * stack trace, SQL, database message or underlying conversion detail is exposed.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex
    ) {
        String parameterName = (ex.getName() != null && !ex.getName().isBlank())
                ? ex.getName()
                : "request";

        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(parameterName, "The provided value is not valid");

        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Invalid request parameter",
                OffsetDateTime.now(),
                errors
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    @ExceptionHandler(OtpNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleOtpNotFound(
            OtpNotFoundException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }


    @ExceptionHandler(OtpAlreadyUsedException.class)
    public ResponseEntity<ApiErrorResponse> handleOtpAlreadyUsed(
            OtpAlreadyUsedException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    @ExceptionHandler(OtpExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleOtpExpired(
            OtpExpiredException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }


    @ExceptionHandler(OtpAttemptsExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleOtpAttemptsExceeded(
            OtpAttemptsExceededException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(response);
    }


    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOtp(
            InvalidOtpException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    @ExceptionHandler({
            org.springframework.security.authentication.BadCredentialsException.class,
            org.springframework.security.core.userdetails.UsernameNotFoundException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(Exception ex) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "Invalid email or password",
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(response);
    }

    @ExceptionHandler(org.springframework.security.authentication.DisabledException.class)
    public ResponseEntity<ApiErrorResponse> handleDisabled(
            org.springframework.security.authentication.DisabledException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Account phone number is not verified",
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(response);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Access denied",
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(response);
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(
            org.springframework.security.core.AuthenticationException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "Authentication failed",
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(response);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    @ExceptionHandler(UnauthorizedActionException.class)
    public ResponseEntity<ApiErrorResponse> handleUnauthorizedAction(
            UnauthorizedActionException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(response);
    }

    @ExceptionHandler({
            InvalidStateTransitionException.class,
            IncompleteListingException.class,
            InvalidFileException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequestBusinessExceptions(
            RuntimeException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    @ExceptionHandler(ListingConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleListingConflict(
            ListingConflictException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }

    /**
     * Safety-net for the true overlapping-transaction case detected by Hibernate's JPA {@code @Version}
     * (Phase 3B.6C). When two transactions load the same listing and both try to commit, the loser's
     * versioned UPDATE matches zero rows and Spring raises {@link ObjectOptimisticLockingFailureException};
     * without this handler it would fall through to an unhandled HTTP 500. It is mapped to 409 through the
     * same envelope as a {@link ListingConflictException}. The message is deliberately generic: no SQL,
     * table, constraint or Hibernate internals are exposed. This does NOT globally translate
     * {@code DataIntegrityViolationException} and leaves the Phase 3B.6B duplicate-publication handling
     * untouched; the transaction is already rolled back when this surfaces.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Listing was modified by another request. Please refresh and try again.",
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }

    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleFileStorage(
            FileStorageException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }

    /**
     * Handles malformed or unreadable request bodies (invalid JSON, type mismatch during
     * deserialization, etc.). The previous implementation exposed Jackson internal parsing
     * details, Java class names, and enum value lists to clients via
     * {@code ex.getMostSpecificCause().getMessage()}. This is replaced with a safe generic
     * message; no request body content, Java type information, or parser internals leak.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex
    ) {
        // Log full detail server-side for developer diagnosis (never returned to client)
        log.debug("Malformed request body: {}", ex.getMessage());

        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Malformed request body",
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    // ─── Catch-all: unexpected/unhandled exceptions ─────────────────────────────

    /**
     * Global fallback for exceptions not handled by any specific {@code @ExceptionHandler}
     * above. Spring resolves handlers by most-specific-type-first, so this only fires for
     * truly unexpected failures (infrastructure errors, unhandled NPEs, etc.).
     *
     * <p>Returns a generic HTTP 500 with a correlation ID logged server-side. Never
     * exposes exception messages, stack traces, SQL, database details, or Java class
     * names to the client.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled exception [correlationId={}]", correlationId, ex);

        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred",
                OffsetDateTime.now(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
}