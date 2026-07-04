package com.warsim.frontline.destruction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DestructionBlockSnapshot(
    DestructionBlockKey key,
    String blockDataString,
    String materialName,
    UUID matchId,
    long capturedLifecycleRevision,
    long order,
    Instant capturedAt
) {
    public DestructionBlockSnapshot {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(blockDataString, "blockDataString");
        Objects.requireNonNull(materialName, "materialName");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        if (blockDataString.isBlank()) {
            throw new IllegalArgumentException("blockDataString must not be blank");
        }
        if (materialName.isBlank()) {
            throw new IllegalArgumentException("materialName must not be blank");
        }
    }
}
