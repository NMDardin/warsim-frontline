package com.warsim.frontline.api.combat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CombatOutcomeSnapshot(
    boolean enabled,
    UUID matchId,
    long lifecycleRevision,
    Map<UUID, PlayerCombatStatistics> statistics,
    List<KillFeedEntry> killFeed,
    CombatOutcomeMetricsSnapshot metrics,
    String lastError
) {
    public CombatOutcomeSnapshot {
        statistics = Map.copyOf(statistics == null ? Map.of() : statistics);
        killFeed = List.copyOf(killFeed == null ? List.of() : killFeed);
    }
}
