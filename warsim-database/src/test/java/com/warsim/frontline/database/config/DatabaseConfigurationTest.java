package com.warsim.frontline.database.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.warsim.frontline.api.database.DatabaseErrorCode;
import com.warsim.frontline.api.database.DatabaseOperationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseConfigurationTest {
    @Test
    void disabledDefaultsAreValid() {
        DatabaseConfiguration configuration = DatabaseConfiguration.disabledDefaults();
        DatabaseConfigurationValidator.validate(configuration);
        assertEquals(false, configuration.enabled());
    }

    @Test
    void rejectsNonPostgreSqlJdbcUrl() {
        DatabaseOperationException exception = assertThrows(
            DatabaseOperationException.class,
            () -> DatabaseConfigurationValidator.validate(valid().withJdbcUrl("jdbc:mysql://localhost/test"))
        );
        assertEquals(DatabaseErrorCode.CONFIGURATION_ERROR, exception.code());
    }

    @Test
    void rejectsUnsafeSchema() {
        assertThrows(
            DatabaseOperationException.class,
            () -> DatabaseConfigurationValidator.validate(valid().withSchema("public;drop table users"))
        );
    }

    @Test
    void rejectsMinimumIdleAboveMaximumSize() {
        assertThrows(
            DatabaseOperationException.class,
            () -> DatabaseConfigurationValidator.validate(valid().withPoolSizes(4, 5))
        );
    }

    @Test
    void rejectsValidationTimeoutAtConnectionTimeout() {
        assertThrows(
            DatabaseOperationException.class,
            () -> DatabaseConfigurationValidator.validate(valid().withTimeouts(5000, 5000))
        );
    }

    @Test
    void rejectsExecutorThreadBoundary() {
        assertThrows(
            DatabaseOperationException.class,
            () -> DatabaseConfigurationValidator.validate(valid().withExecutor(0, 1000, 5000))
        );
        assertThrows(
            DatabaseOperationException.class,
            () -> DatabaseConfigurationValidator.validate(valid().withExecutor(17, 1000, 5000))
        );
    }

    @Test
    void rejectsExecutorQueueBoundary() {
        assertThrows(
            DatabaseOperationException.class,
            () -> DatabaseConfigurationValidator.validate(valid().withExecutor(4, 99, 5000))
        );
        assertThrows(
            DatabaseOperationException.class,
            () -> DatabaseConfigurationValidator.validate(valid().withExecutor(4, 10001, 5000))
        );
    }

    @Test
    void nonEmptyEnvironmentValuesOverrideYaml() {
        DatabaseConfiguration base = valid();
        DatabaseConfiguration overridden = DatabaseEnvironmentOverrides.apply(
            base,
            Map.of(
                "WARSIM_DB_URL", "jdbc:postgresql://db.internal/frontline",
                "WARSIM_DB_USERNAME", "runtime_user",
                "WARSIM_DB_PASSWORD", "runtime_password"
            )
        );
        assertEquals("jdbc:postgresql://db.internal/frontline", overridden.jdbcUrl());
        assertEquals("runtime_user", overridden.username());
        assertEquals("runtime_password", overridden.password());
    }

    @Test
    void emptyEnvironmentValuesDoNotOverrideYaml() {
        DatabaseConfiguration base = valid();
        DatabaseConfiguration overridden = DatabaseEnvironmentOverrides.apply(
            base,
            Map.of("WARSIM_DB_URL", "", "WARSIM_DB_USERNAME", " ", "WARSIM_DB_PASSWORD", "")
        );
        assertEquals(base.jdbcUrl(), overridden.jdbcUrl());
        assertEquals(base.username(), overridden.username());
        assertEquals(base.password(), overridden.password());
    }

    private static DatabaseConfiguration valid() {
        return DatabaseConfiguration.productionDefaults().withPassword("secret");
    }
}
