package com.warsim.frontline.api.combat;

import java.util.UUID;

public record CombatAssistRecord(
    UUID assisterUuid,
    long assisterLifeRevision,
    double damage,
    boolean headshotContribution
) {}
