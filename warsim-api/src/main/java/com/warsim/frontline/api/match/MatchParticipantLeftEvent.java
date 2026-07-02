package com.warsim.frontline.api.match;
import java.time.Instant;
import java.util.UUID;
public record MatchParticipantLeftEvent(UUID matchId, Instant occurredAt, UUID playerUuid)
    implements MatchEvent {}
