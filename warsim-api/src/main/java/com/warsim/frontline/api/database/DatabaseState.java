package com.warsim.frontline.api.database;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle state of the PostgreSQL subsystem.
 */
public enum DatabaseState {
    DISABLED,
    CREATED,
    STARTING,
    MIGRATING,
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    STOPPING,
    STOPPED,
    FAILED;

    public boolean canTransitionTo(DatabaseState target) {
        return transitions().contains(target);
    }

    private Set<DatabaseState> transitions() {
        return switch (this) {
            case DISABLED -> EnumSet.noneOf(DatabaseState.class);
            case CREATED -> EnumSet.of(STARTING, FAILED, STOPPING);
            case STARTING -> EnumSet.of(MIGRATING, HEALTHY, UNAVAILABLE, FAILED, STOPPING);
            case MIGRATING -> EnumSet.of(HEALTHY, UNAVAILABLE, FAILED, STOPPING);
            case HEALTHY -> EnumSet.of(DEGRADED, UNAVAILABLE, STOPPING);
            case DEGRADED -> EnumSet.of(MIGRATING, HEALTHY, UNAVAILABLE, STOPPING);
            case UNAVAILABLE -> EnumSet.of(MIGRATING, HEALTHY, DEGRADED, STOPPING);
            case STOPPING -> EnumSet.of(STOPPED, FAILED);
            case STOPPED, FAILED -> EnumSet.noneOf(DatabaseState.class);
        };
    }
}
