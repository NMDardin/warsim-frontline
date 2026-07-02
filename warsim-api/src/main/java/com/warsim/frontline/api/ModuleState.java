package com.warsim.frontline.api;

/**
 * Lifecycle state shared by all WarSim modules.
 */
public enum ModuleState {
    CREATED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
