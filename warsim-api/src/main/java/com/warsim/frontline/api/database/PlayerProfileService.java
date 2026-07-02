package com.warsim.frontline.api.database;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous player profile operations.
 */
public interface PlayerProfileService {
    CompletableFuture<Optional<PlayerProfile>> findByUuid(UUID playerUuid);

    CompletableFuture<PlayerProfile> createIfAbsent(UUID playerUuid, String currentName, Instant now);

    CompletableFuture<PlayerProfile> updateLastSeen(UUID playerUuid, String currentName, Instant now);

    CompletableFuture<PlayerProfile> upsertOnJoin(UUID playerUuid, String currentName, Instant now);

    CompletableFuture<Long> countProfiles();
}
