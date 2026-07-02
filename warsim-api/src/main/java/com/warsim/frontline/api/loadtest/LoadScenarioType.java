package com.warsim.frontline.api.loadtest;

/** Repeatable load-test scenario family. */
public enum LoadScenarioType {
    ROSTER,
    OBJECTIVE_SINGLE,
    OBJECTIVE_MULTI,
    WEAPON_CLOSE,
    WEAPON_MEDIUM,
    WEAPON_LONG,
    WEAPON_BLOCKED,
    MIXED,
    IDLE,
    CLEANUP
}
