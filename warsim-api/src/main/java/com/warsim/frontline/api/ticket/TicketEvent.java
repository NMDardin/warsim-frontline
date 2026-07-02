package com.warsim.frontline.api.ticket;

import java.time.Instant;
import java.util.UUID;

public sealed interface TicketEvent permits TicketChangedEvent, TicketsDepletedEvent {
    UUID matchId();
    Instant occurredAt();
    long revision();
}
