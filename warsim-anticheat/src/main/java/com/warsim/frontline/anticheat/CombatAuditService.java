package com.warsim.frontline.anticheat;

import java.util.UUID;

public interface CombatAuditService {
    void record(UUID playerUuid, String eventType);
}
