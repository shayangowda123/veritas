package com.veritas.provisioning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.TimeZone;

/**
 * Standalone CLI entry point for secure ADMIN account provisioning.
 *
 * <p>Runs with {@link WebApplicationType#NONE} — no HTTP server is started, no
 * REST endpoints are exposed, and Spring Security filter chains are never
 * registered. The provisioning logic is activated only when the operator
 * explicitly passes {@code --app.admin-provisioning.enabled=true} and provides
 * credentials through environment variables.</p>
 *
 * <h2>Invocation</h2>
 * <pre>
 * ADMIN_PROVISIONING_EMAIL=admin@example.com \
 * ADMIN_PROVISIONING_PASSWORD='s3cur3P@ss!' \
 *   java -cp "target/classes;target/dependency/*" \
 *   com.veritas.provisioning.AdminProvisioningApplication \
 *   --app.admin-provisioning.enabled=true
 * </pre>
 *
 * <h2>Isolation guarantees</h2>
 * <ul>
 *   <li>Component scan is limited to {@code com.veritas.provisioning} — the
 *       web-oriented {@code SecurityConfig}, controllers, JWT filter, and
 *       {@code CustomUserDetailsService} are never loaded.</li>
 *   <li>Entity and repository scanning is explicit so that Hibernate validates
 *       only the tables that already exist in the schema.</li>
 *   <li>A fresh {@link PasswordEncoder} instance is provided here rather than
 *       depending on the web application's security configuration.</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = "com.veritas.provisioning")
@EntityScan(basePackages = "com.veritas")
@EnableJpaRepositories(basePackages = "com.veritas")
@EnableTransactionManagement
public class AdminProvisioningApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication app = new SpringApplication(AdminProvisioningApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }

    /**
     * Provisioning-scoped password encoder. Identical algorithm
     * (BCrypt strength 10) to the web application's encoder, so hashes
     * produced here are compatible with JWT authentication later.
     */
    @Bean
    public PasswordEncoder provisioningPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
