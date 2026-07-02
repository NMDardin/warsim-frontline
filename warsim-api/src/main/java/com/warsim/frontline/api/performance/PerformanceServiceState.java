package com.warsim.frontline.api.performance;

/** Lifecycle state of the local performance sampler. */
public enum PerformanceServiceState {
    DISABLED,
    CREATED,
    ACTIVE,
    SYNTHETIC_RUNNING,
    FAILED,
    CLOSED
}
