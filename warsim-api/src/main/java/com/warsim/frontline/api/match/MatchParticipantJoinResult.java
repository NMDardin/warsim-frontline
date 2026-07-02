package com.warsim.frontline.api.match;

public record MatchParticipantJoinResult(
    boolean accepted,
    boolean created,
    MatchParticipant participant,
    String message
) {
    public static MatchParticipantJoinResult rejected(String message) {
        return new MatchParticipantJoinResult(false, false, null, message);
    }
}
