package com.warsim.frontline.api.objective;

import com.warsim.frontline.api.roster.TeamSide;

public record ObjectiveRewards(int attackersCaptureTickets, int defendersCaptureTickets) {
    public ObjectiveRewards {
        if (attackersCaptureTickets < 0 || attackersCaptureTickets > 1000
            || defendersCaptureTickets < 0 || defendersCaptureTickets > 1000) {
            throw new IllegalArgumentException("Objective ticket rewards must be 0-1000");
        }
    }

    public int forSide(TeamSide side) {
        return side == TeamSide.ATTACKERS
            ? attackersCaptureTickets : defendersCaptureTickets;
    }
}
