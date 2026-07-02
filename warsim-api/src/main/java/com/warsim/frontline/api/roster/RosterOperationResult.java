package com.warsim.frontline.api.roster;

public record RosterOperationResult(
    boolean successful,
    RosterFailure failure,
    String message,
    TeamAssignment assignment
) {
    public static RosterOperationResult success(String message, TeamAssignment assignment) {
        return new RosterOperationResult(true, RosterFailure.NONE, message, assignment);
    }

    public static RosterOperationResult rejected(RosterFailure failure, String message) {
        return new RosterOperationResult(false, failure, message, null);
    }
}
