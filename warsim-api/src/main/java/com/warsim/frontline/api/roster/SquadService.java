package com.warsim.frontline.api.roster;

import com.warsim.frontline.api.match.MatchState;
import java.time.Instant;
import java.util.UUID;

public interface SquadService {
    RosterOperationResult switchSquad(UUID playerUuid, SquadId target, MatchState state, Instant now, boolean administrator);
    RosterOperationResult leaveSquad(UUID playerUuid, Instant now);
    RosterOperationResult transferLeader(UUID actorUuid, UUID targetUuid, boolean administrator, Instant now);
}
