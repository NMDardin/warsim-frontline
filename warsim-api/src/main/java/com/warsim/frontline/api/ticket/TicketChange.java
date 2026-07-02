package com.warsim.frontline.api.ticket;

import com.warsim.frontline.api.roster.TeamSide;
import java.time.Instant;
import java.util.UUID;

public record TicketChange(
    UUID operationId,
    UUID matchId,
    TeamSide teamSide,
    int previousValue,
    int newValue,
    int appliedDelta,
    TicketChangeReason reason,
    Instant occurredAt,
    long revision
) {
}
