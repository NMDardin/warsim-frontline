package com.warsim.frontline.api.database;

/**
 * Point-in-time pool and executor metrics.
 */
public record DatabaseMetricsSnapshot(
    int activeConnections,
    int idleConnections,
    int waitingThreads,
    int activeTasks,
    int queuedTasks,
    long completedTasks
) {
    public static DatabaseMetricsSnapshot empty() {
        return new DatabaseMetricsSnapshot(0, 0, 0, 0, 0, 0);
    }
}
