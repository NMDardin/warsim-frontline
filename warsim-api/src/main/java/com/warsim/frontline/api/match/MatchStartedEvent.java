package com.warsim.frontline.api.match;
import java.time.Instant;
import java.util.UUID;
public record MatchStartedEvent(UUID matchId, Instant occurredAt) implements MatchEvent {}
