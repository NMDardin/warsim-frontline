package com.warsim.frontline.database.config;

import com.warsim.frontline.api.database.DatabaseErrorCode;
import com.warsim.frontline.api.database.DatabaseOperationException;
import java.util.regex.Pattern;

public final class DatabaseConfigurationValidator {
    private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,47}");

    private DatabaseConfigurationValidator() {
    }

    public static void validate(DatabaseConfiguration configuration) {
        require(configuration.jdbcUrl() != null
            && configuration.jdbcUrl().startsWith("jdbc:postgresql://"), "JDBC URL must use PostgreSQL");
        require(configuration.username() != null && !configuration.username().isBlank(), "Username is required");
        require(SCHEMA.matcher(configuration.schema()).matches(), "Schema is invalid");
        require(configuration.maximumPoolSize() >= 1 && configuration.maximumPoolSize() <= 32,
            "maximum-size must be 1-32");
        require(configuration.minimumIdle() >= 0
            && configuration.minimumIdle() <= configuration.maximumPoolSize(),
            "minimum-idle must not exceed maximum-size");
        require(configuration.connectionTimeoutMillis() >= 1000
            && configuration.connectionTimeoutMillis() <= 30000,
            "connection-timeout-millis must be 1000-30000");
        require(configuration.validationTimeoutMillis() >= 250
            && configuration.validationTimeoutMillis() < configuration.connectionTimeoutMillis(),
            "validation-timeout-millis must be below connection timeout");
        require(configuration.idleTimeoutMillis() >= 10000, "idle-timeout-millis must be at least 10000");
        require(configuration.maxLifetimeMillis() >= 30000
            && configuration.maxLifetimeMillis() > configuration.idleTimeoutMillis(),
            "max-lifetime-millis must exceed idle timeout");
        require(configuration.leakDetectionThresholdMillis() == 0
            || configuration.leakDetectionThresholdMillis() >= 2000,
            "leak-detection-threshold-millis must be 0 or at least 2000");
        require(configuration.executorThreads() >= 1 && configuration.executorThreads() <= 16,
            "executor threads must be 1-16");
        require(configuration.executorQueueCapacity() >= 100
            && configuration.executorQueueCapacity() <= 10000,
            "executor queue capacity must be 100-10000");
        require(configuration.shutdownTimeoutMillis() >= 1000
            && configuration.shutdownTimeoutMillis() <= 30000,
            "shutdown timeout must be 1000-30000");
        require(configuration.healthCheckIntervalSeconds() >= 10
            && configuration.healthCheckIntervalSeconds() <= 300,
            "health check interval must be 10-300 seconds");
        if (configuration.enabled()) {
            require(configuration.password() != null && !configuration.password().isBlank(),
                "Enabled database requires a non-empty password");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new DatabaseOperationException(DatabaseErrorCode.CONFIGURATION_ERROR, message);
        }
    }
}
