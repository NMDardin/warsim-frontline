package com.warsim.frontline.api.roster;

import java.util.List;
import java.util.UUID;

public record RosterSnapshot(
    UUID matchId,
    RosterState state,
    long revision,
    List<TeamSnapshot> teams,
    List<SquadSnapshot> squads,
    int activeAssignments,
    int disconnectedReservations,
    String lastError,
    boolean modifiable
) {
    public RosterSnapshot {
        teams = List.copyOf(teams);
        squads = List.copyOf(squads);
    }

    public TeamSnapshot team(TeamSide side) {
        return teams.stream().filter(team -> team.side() == side).findFirst().orElseThrow();
    }

    public SquadSnapshot squad(TeamSide side, SquadId id) {
        return squads.stream()
            .filter(squad -> squad.teamSide() == side && squad.squadId() == id)
            .findFirst().orElseThrow();
    }
}
