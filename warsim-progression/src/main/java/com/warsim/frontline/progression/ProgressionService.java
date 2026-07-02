package com.warsim.frontline.progression;

import java.util.UUID;

/**
 * Commercial Frontline progression boundary, isolated from Euro WarSim data.
 */
public interface ProgressionService {
    long experience(UUID playerUuid);
}
