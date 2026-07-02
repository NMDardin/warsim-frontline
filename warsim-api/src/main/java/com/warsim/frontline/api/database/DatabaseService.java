package com.warsim.frontline.api.database;

import java.util.concurrent.CompletableFuture;

/**
 * Platform-neutral asynchronous database lifecycle and profile service.
 */
public interface DatabaseService extends PlayerProfileService, AutoCloseable {
    boolean enabled();

    String schema();

    DatabaseState state();

    DatabaseHealth health();

    DatabaseMetricsSnapshot metrics();

    CompletableFuture<Void> start();

    CompletableFuture<DatabaseHealth> healthCheck();

    @Override
    void close();
}
