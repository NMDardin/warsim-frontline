package com.warsim.frontline.api.roster;

import java.util.Objects;

public record RosterConfiguration(
    boolean teamsEnabled,
    boolean squadsEnabled,
    int maximumDifference,
    boolean deterministicTieBreak,
    int attackersMaximumPlayers,
    int defendersMaximumPlayers,
    String attackersDisplayName,
    String defendersDisplayName,
    int maximumSquadsPerTeam,
    int maximumMembersPerSquad,
    boolean autoAssignSquads,
    boolean preferExistingSquads,
    boolean switchingEnabled,
    boolean switchDuringWaiting,
    boolean switchDuringWarmup,
    boolean switchDuringPlaying,
    int switchCooldownSeconds,
    boolean reconnectRestoreAssignment,
    int reconnectGraceSeconds
) {
    public RosterConfiguration {
        Objects.requireNonNull(attackersDisplayName, "attackersDisplayName");
        Objects.requireNonNull(defendersDisplayName, "defendersDisplayName");
        if (squadsEnabled && !teamsEnabled) throw new IllegalArgumentException("Squads require teams");
        if (attackersMaximumPlayers < 1 || attackersMaximumPlayers > 50
            || defendersMaximumPlayers < 1 || defendersMaximumPlayers > 50) {
            throw new IllegalArgumentException("Team capacity must be 1-50");
        }
        if (attackersMaximumPlayers + defendersMaximumPlayers > 100) {
            throw new IllegalArgumentException("Total team capacity must not exceed 100");
        }
        if (maximumDifference < 0 || maximumDifference > 10) {
            throw new IllegalArgumentException("maximumDifference must be 0-10");
        }
        if (teamsEnabled && !deterministicTieBreak) {
            throw new IllegalArgumentException("deterministicTieBreak must be enabled");
        }
        if (maximumSquadsPerTeam < 1 || maximumSquadsPerTeam > 10) {
            throw new IllegalArgumentException("maximumSquadsPerTeam must be 1-10");
        }
        if (maximumMembersPerSquad < 1 || maximumMembersPerSquad > 5) {
            throw new IllegalArgumentException("maximumMembersPerSquad must be 1-5");
        }
        if (squadsEnabled
            && maximumSquadsPerTeam * maximumMembersPerSquad
                < Math.max(attackersMaximumPlayers, defendersMaximumPlayers)) {
            throw new IllegalArgumentException("Squad capacity must cover team capacity");
        }
        if (switchCooldownSeconds < 0 || switchCooldownSeconds > 300
            || reconnectGraceSeconds < 0 || reconnectGraceSeconds > 600) {
            throw new IllegalArgumentException("Invalid cooldown or reconnect grace");
        }
        if (attackersDisplayName.isBlank() || attackersDisplayName.length() > 32
            || defendersDisplayName.isBlank() || defendersDisplayName.length() > 32) {
            throw new IllegalArgumentException("Team display names must be 1-32 characters");
        }
    }

    public static RosterConfiguration defaults(boolean enabled) {
        return enabled
            ? new RosterConfiguration(true, true, 1, true, 50, 50, "进攻方", "防守方",
                10, 5, true, true, true, true, true, true, 15, true, 120)
            : disabled();
    }

    public static RosterConfiguration disabled() {
        return new RosterConfiguration(false, false, 1, true, 50, 50, "进攻方", "防守方",
            10, 5, false, true, false, false, false, false, 15, false, 120);
    }

    public int maximumPlayers(TeamSide side) {
        return side == TeamSide.ATTACKERS ? attackersMaximumPlayers : defendersMaximumPlayers;
    }

    public String displayName(TeamSide side) {
        return side == TeamSide.ATTACKERS ? attackersDisplayName : defendersDisplayName;
    }
}
