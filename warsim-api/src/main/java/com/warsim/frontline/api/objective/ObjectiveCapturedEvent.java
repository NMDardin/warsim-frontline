package com.warsim.frontline.api.objective;
import com.warsim.frontline.api.roster.TeamSide;
import java.time.Instant;
import java.util.UUID;
public record ObjectiveCapturedEvent(UUID matchId, ObjectiveId objectiveId, Instant occurredAt,
                                     long revision, TeamSide capturedBy, int ticketReward)
    implements ObjectiveEvent {}
