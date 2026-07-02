package com.warsim.frontline.match.paper;

import com.warsim.frontline.api.roster.SquadSnapshot;
import com.warsim.frontline.api.roster.TeamAssignment;

final class RosterMessages {
    private RosterMessages() {
    }

    static String assignment(TeamAssignment assignment) {
        String squad = assignment.squadId().map(id -> id.displayName() + "(" + id + ")").orElse("无");
        String leader = assignment.squadRole().name().equals("LEADER") ? "，你是队长" : "";
        return "§a已分配阵营：" + assignment.teamSide() + "，小队：" + squad + leader;
    }

    static String squadLine(SquadSnapshot squad) {
        return "§f" + squad.teamSide() + "/" + squad.squadId()
            + "(" + squad.squadId().displayName() + ") §a"
            + squad.currentMembers() + "/" + squad.maximumMembers()
            + " §f队长=" + (squad.leaderUuid() == null ? "无" : squad.leaderUuid());
    }
}
