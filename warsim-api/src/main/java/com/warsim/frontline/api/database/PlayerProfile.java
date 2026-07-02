package com.warsim.frontline.api.database;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable Frontline player identity profile.
 */
public record PlayerProfile(
    UUID playerUuid,
    String currentName,
    Instant createdAt,
    Instant lastSeenAt,
    int profileVersion
) {
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public PlayerProfile {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(currentName, "currentName");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        if (!PLAYER_NAME.matcher(currentName).matches()) {
            throw new IllegalArgumentException("currentName must use Minecraft base name characters");
        }
        if (lastSeenAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("lastSeenAt cannot be before createdAt");
        }
        if (profileVersion < 1) {
            throw new IllegalArgumentException("profileVersion must be positive");
        }
    }
}
