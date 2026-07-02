package com.warsim.frontline.api.roster;

import com.warsim.frontline.api.match.MatchState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TeamService {
    Optional<TeamAssignment> assignment(UUID playerUuid);
    RosterOperationResult moveTeam(UUID playerUuid, TeamSide target, boolean force, MatchState state, Instant now);
    RosterOperationResult rebalance(UUID playerUuid, MatchState state, Instant now);
}
