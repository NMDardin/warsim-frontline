package com.warsim.frontline.api.ticket;

import com.warsim.frontline.api.roster.TeamSide;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TicketOperation(
    UUID operationId,
    TeamSide teamSide,
    TicketOperationType type,
    int amount,
    TicketChangeReason reason,
    Instant occurredAt
) {
    public TicketOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(teamSide, "teamSide");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (amount < 0) throw new IllegalArgumentException("amount cannot be negative");
    }
}
