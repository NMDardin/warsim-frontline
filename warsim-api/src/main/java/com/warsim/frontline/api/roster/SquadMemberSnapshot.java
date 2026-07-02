package com.warsim.frontline.api.roster;

import java.time.Instant;
import java.util.UUID;

public record SquadMemberSnapshot(
    UUID playerUuid,
    String currentName,
    SquadRole role,
    Instant joinedAt,
    boolean connected
) {
}
