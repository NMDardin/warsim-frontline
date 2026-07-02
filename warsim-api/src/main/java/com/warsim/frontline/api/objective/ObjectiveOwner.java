package com.warsim.frontline.api.objective;

import com.warsim.frontline.api.roster.TeamSide;

public enum ObjectiveOwner {
    NEUTRAL,
    ATTACKERS,
    DEFENDERS;

    public static ObjectiveOwner from(TeamSide side) {
        return side == TeamSide.ATTACKERS ? ATTACKERS : DEFENDERS;
    }

    public TeamSide teamSide() {
        return switch (this) {
            case ATTACKERS -> TeamSide.ATTACKERS;
            case DEFENDERS -> TeamSide.DEFENDERS;
            case NEUTRAL -> null;
        };
    }
}
