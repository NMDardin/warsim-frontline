package com.warsim.frontline.database.config;

public record DatabaseConfiguration(
    boolean enabled,
    String jdbcUrl,
    String username,
    String password,
    String schema,
    boolean migrationsEnabled,
    int maximumPoolSize,
    int minimumIdle,
    long connectionTimeoutMillis,
    long validationTimeoutMillis,
    long idleTimeoutMillis,
    long maxLifetimeMillis,
    long leakDetectionThresholdMillis,
    int executorThreads,
    int executorQueueCapacity,
    long shutdownTimeoutMillis,
    int healthCheckIntervalSeconds
) {
    public static DatabaseConfiguration productionDefaults() {
        return new DatabaseConfiguration(
            true,
            "jdbc:postgresql://127.0.0.1:5432/warsim_frontline",
            "warsim_frontline",
            "",
            "warsim_frontline",
            true,
            8,
            2,
            5000,
            3000,
            600000,
            1800000,
            0,
            4,
            1000,
            5000,
            30
        );
    }

    public static DatabaseConfiguration disabledDefaults() {
        DatabaseConfiguration defaults = productionDefaults();
        return defaults.withEnabled(false);
    }

    public DatabaseConfiguration withEnabled(boolean value) {
        return copy(value, jdbcUrl, username, password, schema, maximumPoolSize, minimumIdle,
            connectionTimeoutMillis, validationTimeoutMillis, executorThreads,
            executorQueueCapacity, shutdownTimeoutMillis);
    }

    public DatabaseConfiguration withJdbcUrl(String value) {
        return copy(enabled, value, username, password, schema, maximumPoolSize, minimumIdle,
            connectionTimeoutMillis, validationTimeoutMillis, executorThreads,
            executorQueueCapacity, shutdownTimeoutMillis);
    }

    public DatabaseConfiguration withCredentials(String newUsername, String newPassword) {
        return copy(enabled, jdbcUrl, newUsername, newPassword, schema, maximumPoolSize, minimumIdle,
            connectionTimeoutMillis, validationTimeoutMillis, executorThreads,
            executorQueueCapacity, shutdownTimeoutMillis);
    }

    public DatabaseConfiguration withPassword(String value) {
        return withCredentials(username, value);
    }

    public DatabaseConfiguration withSchema(String value) {
        return copy(enabled, jdbcUrl, username, password, value, maximumPoolSize, minimumIdle,
            connectionTimeoutMillis, validationTimeoutMillis, executorThreads,
            executorQueueCapacity, shutdownTimeoutMillis);
    }

    public DatabaseConfiguration withPoolSizes(int maximum, int minimum) {
        return copy(enabled, jdbcUrl, username, password, schema, maximum, minimum,
            connectionTimeoutMillis, validationTimeoutMillis, executorThreads,
            executorQueueCapacity, shutdownTimeoutMillis);
    }

    public DatabaseConfiguration withTimeouts(long connection, long validation) {
        return copy(enabled, jdbcUrl, username, password, schema, maximumPoolSize, minimumIdle,
            connection, validation, executorThreads, executorQueueCapacity, shutdownTimeoutMillis);
    }

    public DatabaseConfiguration withExecutor(int threads, int capacity, long shutdown) {
        return copy(enabled, jdbcUrl, username, password, schema, maximumPoolSize, minimumIdle,
            connectionTimeoutMillis, validationTimeoutMillis, threads, capacity, shutdown);
    }

    private DatabaseConfiguration copy(
        boolean newEnabled,
        String newJdbcUrl,
        String newUsername,
        String newPassword,
        String newSchema,
        int newMaximumPoolSize,
        int newMinimumIdle,
        long newConnectionTimeout,
        long newValidationTimeout,
        int newExecutorThreads,
        int newQueueCapacity,
        long newShutdownTimeout
    ) {
        return new DatabaseConfiguration(
            newEnabled, newJdbcUrl, newUsername, newPassword, newSchema, migrationsEnabled,
            newMaximumPoolSize, newMinimumIdle, newConnectionTimeout, newValidationTimeout,
            idleTimeoutMillis, maxLifetimeMillis, leakDetectionThresholdMillis, newExecutorThreads,
            newQueueCapacity, newShutdownTimeout, healthCheckIntervalSeconds
        );
    }
}
