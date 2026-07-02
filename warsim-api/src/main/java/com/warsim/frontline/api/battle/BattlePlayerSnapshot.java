package com.warsim.frontline.api.battle;

import com.warsim.frontline.api.match.MatchParticipantState;
import com.warsim.frontline.api.roster.TeamAssignment;
import com.warsim.frontline.api.classes.PlayerCombatState;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable qualification view for a player on the current battle node.
 */
public record BattlePlayerSnapshot(
    UUID playerUuid,
    boolean localSessionActive,
    UUID matchId,
    MatchParticipantState participantState,
    Optional<TeamAssignment> assignment,
    boolean spectator,
    PlayerCombatState combatState,
    long lifeRevision
) {
    public BattlePlayerSnapshot {
        Objects.requireNonNull(playerUuid, "playerUuid");
        assignment = assignment == null ? Optional.empty() : assignment;
        combatState = combatState == null ? PlayerCombatState.NOT_DEPLOYED : combatState;
    }

    public BattlePlayerSnapshot(
        UUID playerUuid,
        boolean localSessionActive,
        UUID matchId,
        MatchParticipantState participantState,
        Optional<TeamAssignment> assignment,
        boolean spectator
    ) {
        this(
            playerUuid, localSessionActive, matchId, participantState, assignment, spectator,
            PlayerCombatState.ALIVE, 0
        );
    }

    public boolean activeFor(UUID expectedMatchId) {
        return localSessionActive
            && Objects.equals(matchId, expectedMatchId)
            && participantState == MatchParticipantState.ACTIVE
            && !spectator
            && combatState == PlayerCombatState.ALIVE
            && assignment.filter(value ->
                value.connected() && value.matchId().equals(expectedMatchId)
            ).isPresent();
    }
}
