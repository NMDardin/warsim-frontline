package com.warsim.frontline.api.objective;

import com.warsim.frontline.api.roster.TeamSide;
import java.time.Instant;
import java.util.UUID;

public record ObjectiveSnapshot(
    UUID matchId,
    ObjectiveId objectiveId,
    String displayName,
    ObjectiveOwner owner,
    ObjectiveState state,
    double progress,
    TeamSide progressingSide,
    int attackersPresent,
    int defendersPresent,
    boolean locked,
    Instant stateChangedAt,
    long revision
) {
}
