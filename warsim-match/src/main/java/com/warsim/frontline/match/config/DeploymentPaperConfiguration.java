package com.warsim.frontline.match.config;

import com.warsim.frontline.api.classes.DeploymentTicketCosts;
import com.warsim.frontline.api.roster.TeamSide;
import java.util.Map;
import java.util.Objects;

public record DeploymentPaperConfiguration(
    boolean enabled,
    String waitingGameModeName,
    String combatGameModeName,
    int countdownSeconds,
    int safeRadius,
    int maximumSpawnCandidates,
    DeploymentTicketCosts ticketCosts,
    SpawnPoint waitingSpawn,
    Map<TeamSide, SpawnPoint> teamSpawns
) {
    public DeploymentPaperConfiguration {
        Objects.requireNonNull(waitingGameModeName, "waitingGameModeName");
        Objects.requireNonNull(combatGameModeName, "combatGameModeName");
        Objects.requireNonNull(ticketCosts, "ticketCosts");
        waitingGameModeName = normalizeGameMode(waitingGameModeName, "waitingGameModeName");
        combatGameModeName = normalizeGameMode(combatGameModeName, "combatGameModeName");
        teamSpawns = Map.copyOf(Objects.requireNonNull(teamSpawns, "teamSpawns"));
        if (countdownSeconds < 0 || countdownSeconds > 60) {
            throw new IllegalArgumentException("deployment countdown must be 0-60 seconds");
        }
        if (safeRadius < 0 || safeRadius > 8) {
            throw new IllegalArgumentException("deployment safe-radius must be 0-8");
        }
        if (maximumSpawnCandidates < 1 || maximumSpawnCandidates > 128) {
            throw new IllegalArgumentException("maximum spawn candidates must be 1-128");
        }
        if (enabled) {
            Objects.requireNonNull(waitingSpawn, "waitingSpawn");
            if (!teamSpawns.containsKey(TeamSide.ATTACKERS)
                || !teamSpawns.containsKey(TeamSide.DEFENDERS)) {
                throw new IllegalArgumentException("enabled deployment requires fixed team spawns");
            }
        }
    }

    public static DeploymentPaperConfiguration disabled() {
        return new DeploymentPaperConfiguration(
            false,
            "SPECTATOR",
            "SURVIVAL",
            5,
            2,
            128,
            DeploymentTicketCosts.defaults(),
            null,
            Map.of()
        );
    }

    private static String normalizeGameMode(String value, String field) {
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z_]{1,32}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    public record SpawnPoint(
        String id,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String note
    ) {
        public SpawnPoint {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(note, "note");
            if (id.isBlank() || id.length() > 48) {
                throw new IllegalArgumentException("spawn id must be 1-48 chars");
            }
            if (world.isBlank() || world.length() > 64) {
                throw new IllegalArgumentException("spawn world must be configured");
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("spawn coordinates must be finite");
            }
        }
    }
}
