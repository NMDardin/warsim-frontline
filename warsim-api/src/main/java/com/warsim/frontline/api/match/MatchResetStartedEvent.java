package com.warsim.frontline.api.match;
import java.time.Instant;
import java.util.UUID;
public record MatchResetStartedEvent(UUID matchId, Instant occurredAt) implements MatchEvent {}
