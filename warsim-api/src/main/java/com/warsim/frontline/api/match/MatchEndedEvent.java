package com.warsim.frontline.api.match;
import java.time.Instant;
import java.util.UUID;
public record MatchEndedEvent(UUID matchId, Instant occurredAt, MatchEndReason reason)
    implements MatchEvent {}
