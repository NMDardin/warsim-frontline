package com.warsim.frontline.api.match;
import java.time.Instant;
import java.util.UUID;
public record MatchStateChangedEvent(UUID matchId, Instant occurredAt, MatchTransition transition)
    implements MatchEvent {}
