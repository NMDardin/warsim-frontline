package com.warsim.frontline.api.classes;

import com.warsim.frontline.api.roster.TeamSide;

public record DeploymentTicketCosts(
    int initialAttackers,
    int initialDefenders,
    int respawnAttackers,
    int respawnDefenders
) {
    public DeploymentTicketCosts {
        if (initialAttackers < 0 || initialDefenders < 0
            || respawnAttackers < 0 || respawnDefenders < 0) {
            throw new IllegalArgumentException("ticket costs cannot be negative");
        }
    }

    public int cost(DeploymentReason reason, TeamSide side) {
        return switch (reason) {
            case INITIAL_DEPLOYMENT -> side == TeamSide.ATTACKERS
                ? initialAttackers : initialDefenders;
            case RESPAWN -> side == TeamSide.ATTACKERS
                ? respawnAttackers : respawnDefenders;
        };
    }

    public static DeploymentTicketCosts defaults() {
        return new DeploymentTicketCosts(0, 0, 1, 0);
    }
}
