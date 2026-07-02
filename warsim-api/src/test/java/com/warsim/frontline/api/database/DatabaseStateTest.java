package com.warsim.frontline.api.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseStateTest {
    @Test
    void exposesLegalLifecycleTransitions() {
        assertTrue(DatabaseState.CREATED.canTransitionTo(DatabaseState.STARTING));
        assertTrue(DatabaseState.STARTING.canTransitionTo(DatabaseState.MIGRATING));
        assertTrue(DatabaseState.MIGRATING.canTransitionTo(DatabaseState.HEALTHY));
        assertTrue(DatabaseState.DEGRADED.canTransitionTo(DatabaseState.HEALTHY));
        assertTrue(DatabaseState.UNAVAILABLE.canTransitionTo(DatabaseState.HEALTHY));
        assertTrue(DatabaseState.DEGRADED.canTransitionTo(DatabaseState.MIGRATING));
        assertTrue(DatabaseState.UNAVAILABLE.canTransitionTo(DatabaseState.MIGRATING));
        assertTrue(DatabaseState.HEALTHY.canTransitionTo(DatabaseState.STOPPING));
    }

    @Test
    void rejectsUnsafeLifecycleTransitions() {
        assertFalse(DatabaseState.DISABLED.canTransitionTo(DatabaseState.HEALTHY));
        assertFalse(DatabaseState.FAILED.canTransitionTo(DatabaseState.HEALTHY));
        assertFalse(DatabaseState.STOPPED.canTransitionTo(DatabaseState.STARTING));
    }
}
