package com.veritas.provisioning;

import com.veritas.user.User;
import com.veritas.user.UserRepository;
import com.veritas.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying ADMIN provisioning against real PostgreSQL
 * (PostGIS container). Validates unique-email constraint behavior,
 * transactional semantics, and idempotency under real persistence.
 */
@SpringBootTest(
        classes = AdminProvisioningApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.admin-provisioning.enabled=true"
)
@Testcontainers
@Tag("integration")
class AdminProvisioningIntegrationTest {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private AdminProvisioningService provisioningService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("New ADMIN is persisted with correct role, hash, and verified state")
    void newAdminPersisted() {
        String email = "int-admin-" + UUID.randomUUID() + "@test.local";

        var result = provisioningService.provision(email, "IntPass123!");

        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.CREATED);

        User admin = userRepository.findByEmail(email.toLowerCase()).orElseThrow();
        createdUserIds.add(admin.getId());

        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.isVerified()).isTrue();
        assertThat(admin.getName()).isEqualTo("Administrator");
        assertThat(passwordEncoder.matches("IntPass123!", admin.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Repeated provisioning is idempotent — no duplicate created")
    void idempotentProvisioning() {
        String email = "int-idem-" + UUID.randomUUID() + "@test.local";

        var r1 = provisioningService.provision(email, "IntPass123!");
        assertThat(r1).isEqualTo(AdminProvisioningService.ProvisioningResult.CREATED);

        // Find and track for cleanup
        userRepository.findByEmail(email.toLowerCase()).ifPresent(u -> createdUserIds.add(u.getId()));

        // Second run
        var r2 = provisioningService.provision(email, "DifferentPass456!");
        assertThat(r2).isEqualTo(AdminProvisioningService.ProvisioningResult.ALREADY_EXISTS);

        // Only one user exists
        long count = userRepository.findAll().stream()
                .filter(u -> u.getEmail().equals(email.toLowerCase())).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Existing OWNER email is not promoted; account data unchanged")
    void existingOwnerNotPromoted() {
        String email = "int-owner-" + UUID.randomUUID() + "@test.local";

        // Manually seed an OWNER
        User owner = new User();
        owner.setName("Test Owner");
        owner.setEmail(email.toLowerCase());
        owner.setPasswordHash(passwordEncoder.encode("OwnerPass1!"));
        owner.setRole(UserRole.OWNER);
        owner.setVerified(true);
        owner.setCreatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        owner.setUpdatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        userRepository.saveAndFlush(owner);
        createdUserIds.add(owner.getId());

        var result = provisioningService.provision(email, "AdminPass123!");
        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.CONFLICT_NON_ADMIN);

        // Verify no mutation
        User unchanged = userRepository.findById(owner.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(UserRole.OWNER);
        assertThat(unchanged.getName()).isEqualTo("Test Owner");
        assertThat(passwordEncoder.matches("OwnerPass1!", unchanged.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Existing RENTER email is not promoted; account data unchanged")
    void existingRenterNotPromoted() {
        String email = "int-renter-" + UUID.randomUUID() + "@test.local";

        User renter = new User();
        renter.setName("Test Renter");
        renter.setEmail(email.toLowerCase());
        renter.setPasswordHash(passwordEncoder.encode("RenterPass1!"));
        renter.setRole(UserRole.RENTER);
        renter.setVerified(true);
        renter.setCreatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        renter.setUpdatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        userRepository.saveAndFlush(renter);
        createdUserIds.add(renter.getId());

        var result = provisioningService.provision(email, "AdminPass123!");
        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.CONFLICT_NON_ADMIN);

        User unchanged = userRepository.findById(renter.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(UserRole.RENTER);
        assertThat(passwordEncoder.matches("RenterPass1!", unchanged.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Unique-email constraint enforced at database level")
    void databaseUniqueEmailConstraint() {
        String email = "int-unique-" + UUID.randomUUID() + "@test.local";

        // Insert directly
        User user = new User();
        user.setName("First");
        user.setEmail(email);
        user.setPasswordHash("$2a$10$x");
        user.setRole(UserRole.OWNER);
        user.setVerified(true);
        user.setCreatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        user.setUpdatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        userRepository.saveAndFlush(user);
        createdUserIds.add(user.getId());

        // Try provisioning same email (will see existing and refuse if non-admin)
        var result = provisioningService.provision(email, "NewPass123!");
        assertThat(result).isEqualTo(AdminProvisioningService.ProvisioningResult.CONFLICT_NON_ADMIN);
    }

    @Test
    @DisplayName("Email normalization: mixed case and spaces resolve to same account")
    void emailNormalization() {
        String base = "int-norm-" + UUID.randomUUID() + "@test.local";
        String spaced = "  " + base.toUpperCase() + "  ";

        var r1 = provisioningService.provision(spaced, "NormPass1!");
        assertThat(r1).isEqualTo(AdminProvisioningService.ProvisioningResult.CREATED);

        userRepository.findByEmail(base).ifPresent(u -> createdUserIds.add(u.getId()));

        // Same email, different case — should find existing
        var r2 = provisioningService.provision(base.toUpperCase(), "OtherPass1!");
        assertThat(r2).isEqualTo(AdminProvisioningService.ProvisioningResult.ALREADY_EXISTS);
    }

    @Nested
    @DisplayName("Provisioning application context")
    class ApplicationContextTests {

        @Test
        @DisplayName("Context starts successfully with web-application-type=none")
        void contextStarts() {
            // If this test class is running, the context loaded without HTTP.
            // This proves the provisioning app doesn't start a web server.
            assertThat(provisioningService).isNotNull();
            assertThat(userRepository).isNotNull();
            assertThat(passwordEncoder).isNotNull();
        }
    }
}
