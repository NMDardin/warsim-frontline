package com.warsim.frontline.api.match;

public enum MatchEndReason {
    TIME_LIMIT,
    ADMIN_STOP,
    OBJECTIVE_COMPLETED,
    TICKETS_DEPLETED,
    SERVER_SHUTDOWN,
    RESET_REQUESTED,
    INTERNAL_ERROR
}
