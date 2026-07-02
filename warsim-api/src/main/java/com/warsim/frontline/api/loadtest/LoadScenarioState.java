package com.warsim.frontline.api.loadtest;

/** Local state of the load scenario service. */
public enum LoadScenarioState {
    DISABLED,
    UNLOADED,
    VALIDATING,
    READY,
    PREPARED,
    DIRTY,
    CLEANING,
    FAILED,
    CLOSED
}
