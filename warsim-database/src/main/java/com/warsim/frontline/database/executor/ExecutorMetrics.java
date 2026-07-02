package com.warsim.frontline.database.executor;

public record ExecutorMetrics(int activeTasks, int queuedTasks, long completedTasks) {
}
