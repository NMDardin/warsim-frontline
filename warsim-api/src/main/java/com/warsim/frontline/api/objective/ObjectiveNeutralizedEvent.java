package com.warsim.frontline.api.objective;
import com.warsim.frontline.api.roster.TeamSide;
import java.time.Instant;
import java.util.UUID;
public record ObjectiveNeutralizedEvent(UUID matchId, ObjectiveId objectiveId, Instant occurredAt,
                                        long revision, TeamSide neutralizedBy)
    implements ObjectiveEvent {}
