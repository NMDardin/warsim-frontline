package com.warsim.frontline.api.match;
import java.time.Instant;
import java.util.UUID;
public record MatchResetCompletedEvent(UUID matchId, Instant occurredAt) implements MatchEvent {}
