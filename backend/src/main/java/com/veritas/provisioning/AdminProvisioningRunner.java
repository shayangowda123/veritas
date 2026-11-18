package com.veritas.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * CLI execution hook that performs ADMIN provisioning and then shuts down the
 * application.
 *
 * <p>The bean is only created when {@code app.admin-provisioning.enabled=true}
 * is explicitly supplied (e.g. via command-line flag). Under normal web
 * application startup this property is absent, so the bean is never instantiated
 * and no provisioning logic executes.</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin-provisioning.enabled", havingValue = "true")
public class AdminProvisioningRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminProvisioningRunner.class);

    private static final String ENV_EMAIL = "ADMIN_PROVISIONING_EMAIL";
    private static final String ENV_PASSWORD = "ADMIN_PROVISIONING_PASSWORD";

    private final AdminProvisioningService provisioningService;
    private int exitCode;

    public AdminProvisioningRunner(AdminProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = System.getenv(ENV_EMAIL);
        String password = System.getenv(ENV_PASSWORD);

        if (email == null || email.isBlank()) {
            log.error("Required environment variable {} is not set or blank", ENV_EMAIL);
            exitCode = 1;
            return;
        }
        if (password == null || password.isBlank()) {
            log.error("Required environment variable {} is not set or blank", ENV_PASSWORD);
            exitCode = 1;
            return;
        }

        AdminProvisioningService.ProvisioningResult result =
                provisioningService.provision(email, password);

        switch (result) {
            case CREATED -> {
                log.info("ADMIN account provisioned successfully");
                exitCode = 0;
            }
            case ALREADY_EXISTS -> {
                log.info("ADMIN account already exists — idempotent no-op");
                exitCode = 0;
            }
            case CONFLICT_NON_ADMIN -> {
                log.error("Cannot provision: email belongs to a non-ADMIN account");
                exitCode = 2;
            }
            case INVALID_EMAIL, INVALID_PASSWORD -> {
                log.error("Provisioning aborted due to invalid credential input");
                exitCode = 1;
            }
            case DUPLICATE_RACE -> {
                log.error("Concurrent provisioning conflict — verify manually");
                exitCode = 3;
            }
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
