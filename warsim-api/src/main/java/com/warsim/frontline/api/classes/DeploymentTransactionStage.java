package com.warsim.frontline.api.classes;

public enum DeploymentTransactionStage {
    CREATED,
    VALIDATED,
    LOADOUT_PREPARED,
    TICKET_CHARGED,
    TELEPORTED,
    LOADOUT_GRANTED,
    HEALTH_RESTORED,
    COMMITTED,
    ROLLING_BACK,
    ROLLED_BACK,
    FAILED
}
