package com.warsim.frontline.database.config;

import java.util.Map;

public final class DatabaseEnvironmentOverrides {
    private DatabaseEnvironmentOverrides() {
    }

    public static DatabaseConfiguration apply(
        DatabaseConfiguration configuration,
        Map<String, String> environment
    ) {
        String url = nonBlank(environment.get("WARSIM_DB_URL"), configuration.jdbcUrl());
        String username = nonBlank(environment.get("WARSIM_DB_USERNAME"), configuration.username());
        String password = nonBlank(environment.get("WARSIM_DB_PASSWORD"), configuration.password());
        return configuration.withJdbcUrl(url).withCredentials(username, password);
    }

    private static String nonBlank(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }
}
