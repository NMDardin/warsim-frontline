package com.warsim.frontline.api.ticket;

import java.time.Instant;

public record TicketMetricsSnapshot(
    int currentAttackersTickets,
    long ticketChanges,
    long ticketsAdded,
    long ticketsRemoved,
    long objectiveRewards,
    long rejectedOperations,
    long duplicateOperations,
    long depletedEvents,
    Instant lastChangedAt
) {
}
