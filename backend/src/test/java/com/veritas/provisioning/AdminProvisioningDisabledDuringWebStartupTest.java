package com.veritas.provisioning;

import com.veritas.VeritasApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the normal web application context does NOT activate the
 * ADMIN provisioning runner. This ensures the CLI mechanism cannot
 * accidentally run during regular deployment.
 *
 * <p>Uses the real {@link VeritasApplication} with a servlet web environment
 * backed by a Testcontainers PostGIS instance. The provisioning property
 * is NOT set, so the runner bean must be absent.</p>
 */
@SpringBootTest(classes = VeritasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@Tag("integration")
class AdminProvisioningDisabledDuringWebStartupTest {

    @Container
    static PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:18-3.6")
                    .asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("veritas.geocoding.enabled", () -> "false");
        registry.add("security.jwt.secret-base64",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci12ZXJpdGFzLXJlbnRhbC1hcHAtMjU2LWJpdC1taW5pbXVtIQ==");
        registry.add("security.jwt.expiration-ms", () -> "3600000");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("AdminProvisioningRunner bean is absent from normal web application context")
    void runnerNotRegisteredInWebApp() {
        // The runner is @ConditionalOnProperty; without app.admin-provisioning.enabled=true
        // the bean must not exist.
        assertThat(context.getBeanNamesForType(AdminProvisioningRunner.class)).isEmpty();
    }

    @Test
    @DisplayName("AdminProvisioningService bean is present but runner is not — no auto-provisioning")
    void serviceBeanExistsButNoRunnerTrigger() {
        // Service is scanned (VeritasApplication scans com.veritas and sub-packages).
        // Without the runner, provision() is never called during startup.
        boolean hasService = context.getBeanNamesForType(AdminProvisioningService.class).length > 0;
        boolean hasRunner = context.getBeanNamesForType(AdminProvisioningRunner.class).length > 0;
        assertThat(hasService).isTrue();
        assertThat(hasRunner).isFalse();
    }
}
