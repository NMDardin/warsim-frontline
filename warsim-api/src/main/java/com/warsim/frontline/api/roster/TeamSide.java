package com.warsim.frontline.api.roster;

public enum TeamSide {
    ATTACKERS,
    DEFENDERS;

    public TeamSide opposite() {
        return this == ATTACKERS ? DEFENDERS : ATTACKERS;
    }
}
