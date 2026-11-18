package com.veritas.common.exception;

import com.veritas.listing.ListingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone (no security, no database) tests for the shared {@link GlobalExceptionHandler}
 * error envelope. Uses a minimal probe controller purely to provoke request-parameter and
 * path-variable type-conversion failures.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Invalid enum request parameter returns a 400 ApiErrorResponse with a populated errors map")
    void invalidEnumRequestParamReturns400Envelope() throws Exception {
        mockMvc.perform(get("/probe/enum").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.errors").exists())
                .andExpect(jsonPath("$.errors.status").exists());
    }

    @Test
    @DisplayName("Malformed UUID path variable returns a 400 ApiErrorResponse with a populated errors map")
    void malformedUuidPathReturns400Envelope() throws Exception {
        mockMvc.perform(get("/probe/uuid/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").exists())
                .andExpect(jsonPath("$.errors.id").exists());
    }

    @Test
    @DisplayName("Type-mismatch message is safe: no exception class names, conversion detail or stack frames leak")
    void typeMismatchMessageIsSafe() throws Exception {
        String body = mockMvc.perform(get("/probe/enum").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("MethodArgumentTypeMismatchException"), "must not leak exception class name");
        assertFalse(body.contains("Failed to convert"), "must not leak internal conversion detail");
        assertFalse(body.contains("java.lang"), "must not leak java type names");
        assertFalse(body.contains("at com."), "must not leak stack trace frames");
    }

    @Test
    @DisplayName("ObjectOptimisticLockingFailureException (Hibernate @Version overlap) is mapped to a safe 409 envelope")
    void optimisticLockYields409Envelope() throws Exception {
        String body = mockMvc.perform(get("/probe/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Listing was modified by another request. Please refresh and try again."))
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("ObjectOptimisticLockingFailureException"), "must not leak exception class name");
        assertFalse(body.contains("Hibernate"), "must not leak persistence-provider detail");
        assertFalse(body.contains("java.lang"), "must not leak java type names");
        assertFalse(body.contains("at com."), "must not leak stack trace frames");
    }

    @Test
    @DisplayName("Optimistic-lock handler is exact: only ObjectOptimisticLockingFailureException, never DataIntegrityViolationException")
    void optimisticLockHandlerIsNarrowlyScoped() throws Exception {
        Method method = GlobalExceptionHandler.class
                .getDeclaredMethod("handleOptimisticLock", ObjectOptimisticLockingFailureException.class);
        ExceptionHandler handler = method.getAnnotation(ExceptionHandler.class);
        assertEquals(1, handler.value().length, "handler must cover exactly one exception type");
        assertEquals(ObjectOptimisticLockingFailureException.class, handler.value()[0]);
        // DataIntegrityViolationException and its DuplicateKeyException subtype stay untranslated
        // globally; the narrow 3B.6B publication-conflict handling remains inside ListingService.
        assertFalse(DataIntegrityViolationException.class
                        .isAssignableFrom(handler.value()[0]),
                "optimistic-lock handler must not widen to DataIntegrityViolationException");
    }

    @Test
    @DisplayName("No global handler maps DataIntegrityViolationException (or its DuplicateKeyException subtype) to 409")
    void doesNotGloballyMapDataIntegrityViolations() {
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            ExceptionHandler handler = method.getAnnotation(ExceptionHandler.class);
            if (handler == null) {
                continue;
            }
            for (Class<? extends Throwable> type : handler.value()) {
                // The generic Exception.class catch-all (500 fallback) is excluded:
                // it does NOT specifically handle DataIntegrityViolation as 409.
                if (type == Exception.class) {
                    continue;
                }
                assertFalse(type.isAssignableFrom(DataIntegrityViolationException.class),
                        () -> method.getName() + " globally handles DataIntegrityViolationException via " + type.getName());
                assertFalse(type.isAssignableFrom(DuplicateKeyException.class),
                        () -> method.getName() + " globally handles DuplicateKeyException via " + type.getName());
            }
        }
    }

    // ─── Phase 3B.7B: Information Disclosure Hardening Tests ───────────────────

    @Nested
    @DisplayName("Phase 3B.7B: Malformed JSON body hardening")
    class MalformedJsonTests {

        @Test
        @DisplayName("1. Malformed JSON returns HTTP 400")
        void malformedJson_returns400() throws Exception {
            mockMvc.perform(post("/probe/json")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{invalid json!!}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("2. Malformed JSON response does not expose Java class names")
        void malformedJson_noJavaClassNames() throws Exception {
            String body = mockMvc.perform(post("/probe/json")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{broken"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertFalse(body.contains("com.veritas"), "must not expose package names");
            assertFalse(body.contains("java.lang"), "must not expose java types");
            assertFalse(body.contains("Jackson"), "must not expose Jackson class names");
            assertFalse(body.contains("JsonParseException"), "must not expose parser class");
            assertFalse(body.contains("HttpMessageNotReadableException"), "must not expose exception class");
        }

        @Test
        @DisplayName("3. Malformed JSON response does not expose Jackson internals or field structure")
        void malformedJson_noJacksonInternals() throws Exception {
            // Wrong type for a field — triggers deserialization error with type info
            String body = mockMvc.perform(post("/probe/json")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\": \"not-a-number\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertFalse(body.contains("ProbeDto"), "must not expose DTO class name");
            assertFalse(body.contains("int"), "must not expose Java type info");
            assertFalse(body.contains("String value not valid"), "must not expose Jackson parse detail");
            // The message should be the safe generic one
            assertTrue(body.contains("Malformed request body"), "must use safe generic message");
        }
    }

    @Nested
    @DisplayName("Phase 3B.7B: Unexpected exception (500) hardening")
    class UnexpectedExceptionTests {

        @Test
        @DisplayName("4. Unexpected RuntimeException returns HTTP 500")
        void unexpectedRuntimeException_returns500() throws Exception {
            mockMvc.perform(get("/probe/runtime-boom"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500));
        }

        @Test
        @DisplayName("5. Generic 500 response does not expose exception message")
        void generic500_noExceptionMessage() throws Exception {
            String body = mockMvc.perform(get("/probe/runtime-boom"))
                    .andExpect(status().isInternalServerError())
                    .andReturn().getResponse().getContentAsString();

            // The probe throws with a sensitive message
            assertFalse(body.contains("DB_SECRET_TABLE"), "must not expose exception detail");
            assertFalse(body.contains("connection to db-host:5432 failed"), "must not expose infra detail");
            assertTrue(body.contains("An unexpected error occurred"), "must use safe generic message");
        }

        @Test
        @DisplayName("6. Generic 500 response does not expose stack traces")
        void generic500_noStackTrace() throws Exception {
            String body = mockMvc.perform(get("/probe/runtime-boom"))
                    .andExpect(status().isInternalServerError())
                    .andReturn().getResponse().getContentAsString();

            assertFalse(body.contains("at com."), "must not expose stack frames");
            assertFalse(body.contains("at java."), "must not expose JDK frames");
            assertFalse(body.contains("Caused by"), "must not expose causal chain");
            assertFalse(body.contains("RuntimeException"), "must not expose exception class");
        }

        @Test
        @DisplayName("NullPointerException is also caught by the fallback")
        void npe_returns500() throws Exception {
            mockMvc.perform(get("/probe/npe"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
        }
    }

    @Nested
    @DisplayName("Phase 3B.7B: Existing status code preservation")
    class StatusPreservationTests {

        @Test
        @DisplayName("7. Validation errors remain HTTP 400")
        void validationRemains400() throws Exception {
            mockMvc.perform(get("/probe/enum").param("status", "INVALID"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("8. Unauthorized exceptions remain HTTP 401")
        void unauthorizedRemains401() throws Exception {
            mockMvc.perform(get("/probe/unauthorized"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("9. Forbidden exceptions remain HTTP 403")
        void forbiddenRemains403() throws Exception {
            mockMvc.perform(get("/probe/forbidden"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("10. Not-found exceptions remain HTTP 404")
        void notFoundRemains404() throws Exception {
            mockMvc.perform(get("/probe/not-found"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("11. Conflict exceptions remain HTTP 409")
        void conflictRemains409() throws Exception {
            mockMvc.perform(get("/probe/conflict"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("12. Rate-limit exceptions remain HTTP 429")
        void rateLimitRemains429() throws Exception {
            mockMvc.perform(get("/probe/rate-limit"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.status").value(429));
        }
    }

    // ─── Probe Controller ────────────────────────────────────────────────────────

    /**
     * Minimal controller used to trigger exception handlers via standalone MockMvc.
     */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/enum")
        String enumParam(@RequestParam ListingStatus status) {
            return "ok:" + status;
        }

        @GetMapping("/uuid/{id}")
        String uuidParam(@PathVariable UUID id) {
            return "ok:" + id;
        }

        @GetMapping("/optimistic-lock")
        String optimisticLock() {
            throw new ObjectOptimisticLockingFailureException("Listing", "probe-entity-id");
        }

        @PostMapping("/json")
        String json(@RequestBody ProbeDto dto) {
            return "ok:" + dto.value;
        }

        @GetMapping("/runtime-boom")
        String runtimeBoom() {
            throw new RuntimeException("connection to db-host:5432 failed; table=DB_SECRET_TABLE");
        }

        @GetMapping("/npe")
        String npe() {
            String s = null;
            return s.length() + ""; // triggers NullPointerException
        }

        @GetMapping("/unauthorized")
        String unauthorized() {
            throw new org.springframework.security.authentication.BadCredentialsException("bad password");
        }

        @GetMapping("/forbidden")
        String forbidden() {
            throw new org.springframework.security.access.AccessDeniedException("no permission");
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new ResourceNotFoundException("Property not found");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new EmailAlreadyExistsException("Email already registered");
        }

        @GetMapping("/rate-limit")
        String rateLimit() {
            throw new OtpAttemptsExceededException("Maximum OTP attempts exceeded");
        }
    }

    /**
     * Simple DTO with an int field — used to trigger type-mismatch deserialization errors.
     */
    static class ProbeDto {
        public int value;
    }
}
