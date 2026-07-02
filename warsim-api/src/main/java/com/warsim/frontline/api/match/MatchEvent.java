package com.warsim.frontline.api.match;

import java.time.Instant;
import java.util.UUID;

public sealed interface MatchEvent permits
    MatchCreatedEvent,
    MatchStateChangedEvent,
    MatchStartedEvent,
    MatchEndedEvent,
    MatchResetStartedEvent,
    MatchResetCompletedEvent,
    MatchParticipantJoinedEvent,
    MatchParticipantLeftEvent {

    UUID matchId();
    Instant occurredAt();
}
