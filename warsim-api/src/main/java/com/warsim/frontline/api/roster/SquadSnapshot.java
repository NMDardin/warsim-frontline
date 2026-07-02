package com.warsim.frontline.api.roster;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SquadSnapshot(
    SquadId squadId,
    TeamSide teamSide,
    UUID matchId,
    UUID leaderUuid,
    int currentMembers,
    int maximumMembers,
    List<SquadMemberSnapshot> members,
    boolean acceptingMembers,
    Instant createdAt
) {
    public SquadSnapshot {
        members = List.copyOf(members);
    }
}
