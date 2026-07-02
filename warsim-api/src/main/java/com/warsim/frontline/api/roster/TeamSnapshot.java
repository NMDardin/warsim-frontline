package com.warsim.frontline.api.roster;

public record TeamSnapshot(
    TeamSide side,
    String displayName,
    int activeMembers,
    int reservedMembers,
    int maximumPlayers
) {
}
