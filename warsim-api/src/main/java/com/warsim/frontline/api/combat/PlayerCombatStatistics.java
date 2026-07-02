package com.warsim.frontline.api.combat;

import java.util.UUID;

public record PlayerCombatStatistics(
    UUID playerUuid,
    UUID matchId,
    int kills,
    int deaths,
    int assists,
    int headshotKills,
    int teamKills,
    int suicides,
    int environmentalDeaths,
    double damageDealt,
    double damageReceived,
    double longestKillDistance,
    int currentKillStreak,
    int highestKillStreak
) {
    public static PlayerCombatStatistics empty(UUID playerUuid, UUID matchId) {
        return new PlayerCombatStatistics(playerUuid, matchId, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0);
    }
}
