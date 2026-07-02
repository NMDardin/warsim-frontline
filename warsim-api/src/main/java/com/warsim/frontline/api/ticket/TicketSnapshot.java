package com.warsim.frontline.api.ticket;

import java.time.Instant;
import java.util.UUID;

public record TicketSnapshot(
    UUID matchId,
    TicketPool attackers,
    TicketPool defenders,
    long revision,
    boolean attackersDepleted,
    TicketChange lastChange,
    Instant createdAt,
    boolean closed
) {
}
