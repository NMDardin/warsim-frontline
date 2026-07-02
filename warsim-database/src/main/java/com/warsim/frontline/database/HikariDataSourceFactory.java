package com.warsim.frontline.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.warsim.frontline.database.config.DatabaseConfiguration;

public final class HikariDataSourceFactory {
    public HikariDataSource create(DatabaseConfiguration configuration) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("warsim-frontline-db");
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.setJdbcUrl(configuration.jdbcUrl());
        hikari.setUsername(configuration.username());
        hikari.setPassword(configuration.password());
        hikari.setMaximumPoolSize(configuration.maximumPoolSize());
        hikari.setMinimumIdle(configuration.minimumIdle());
        hikari.setConnectionTimeout(configuration.connectionTimeoutMillis());
        hikari.setValidationTimeout(configuration.validationTimeoutMillis());
        hikari.setIdleTimeout(configuration.idleTimeoutMillis());
        hikari.setMaxLifetime(configuration.maxLifetimeMillis());
        hikari.setLeakDetectionThreshold(configuration.leakDetectionThresholdMillis());
        hikari.setInitializationFailTimeout(-1);
        hikari.addDataSourceProperty("tcpKeepAlive", "true");
        hikari.addDataSourceProperty("ApplicationName", "WarSim Frontline");
        return new HikariDataSource(hikari);
    }
}
