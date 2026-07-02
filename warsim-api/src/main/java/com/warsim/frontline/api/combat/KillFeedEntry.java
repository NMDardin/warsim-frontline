package com.warsim.frontline.api.combat;

import java.time.Instant;

public record KillFeedEntry(
    String killerDisplayName,
    String victimDisplayName,
    String weaponDisplayName,
    boolean headshot,
    boolean teamkill,
    double distance,
    Instant generatedAt,
    long expiresAtMonotonic
) {}
