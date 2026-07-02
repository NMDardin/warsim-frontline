package com.warsim.frontline.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.warsim.frontline.database.config.DatabaseConfiguration;
import org.junit.jupiter.api.Test;

class HikariDataSourceFactoryTest {
    @Test void explicitlyConfiguresPostgreSqlDriverForPluginClassLoaders() {
        DatabaseConfiguration configuration = DatabaseConfiguration.productionDefaults()
            .withPassword("test-only")
            .withJdbcUrl("jdbc:postgresql://127.0.0.1:1/warsim_frontline");
        try (var dataSource = new HikariDataSourceFactory().create(configuration)) {
            assertEquals("org.postgresql.Driver", dataSource.getDriverClassName());
        }
    }
}
