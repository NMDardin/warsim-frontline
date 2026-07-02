package com.warsim.frontline.api.objective;
import java.time.Instant;
import java.util.UUID;
public record ObjectiveUnlockedEvent(UUID matchId, ObjectiveId objectiveId, Instant occurredAt,
                                     long revision) implements ObjectiveEvent {}
