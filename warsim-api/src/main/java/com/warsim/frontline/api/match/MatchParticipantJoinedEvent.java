package com.warsim.frontline.api.match;
import java.time.Instant;
import java.util.UUID;
public record MatchParticipantJoinedEvent(UUID matchId, Instant occurredAt, UUID playerUuid)
    implements MatchEvent {}
