package com.warsim.frontline.match.resourcepack;

import java.time.Instant;
import java.util.UUID;

record ResourcePackPlayerRecord(
    UUID playerUuid,
    String playerName,
    ResourcePackPlayerStatus status,
    Instant updatedAt,
    String contentVersion
) {
}
