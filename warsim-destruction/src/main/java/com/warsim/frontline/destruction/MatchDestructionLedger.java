package com.warsim.frontline.destruction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class MatchDestructionLedger {
    private final UUID matchId;
    private final long capturedLifecycleRevision;
    private final LinkedHashMap<DestructionBlockKey, DestructionBlockSnapshot> snapshots =
        new LinkedHashMap<>();
    private long nextOrder;

    MatchDestructionLedger(UUID matchId, long capturedLifecycleRevision) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.capturedLifecycleRevision = capturedLifecycleRevision;
    }

    UUID matchId() {
        return matchId;
    }

    long capturedLifecycleRevision() {
        return capturedLifecycleRevision;
    }

    int size() {
        return snapshots.size();
    }

    long nextOrder() {
        return nextOrder++;
    }

    boolean contains(DestructionBlockKey key) {
        return snapshots.containsKey(key);
    }

    void putIfAbsent(DestructionBlockSnapshot snapshot) {
        snapshots.putIfAbsent(snapshot.key(), snapshot);
    }

    java.util.List<DestructionBlockSnapshot> snapshotsReverseOrder() {
        java.util.ArrayList<DestructionBlockSnapshot> values = new java.util.ArrayList<>(snapshots.values());
        java.util.Collections.reverse(values);
        return values;
    }

    Map<DestructionBlockKey, DestructionBlockSnapshot> snapshotsView() {
        return Map.copyOf(snapshots);
    }
}
