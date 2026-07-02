package com.warsim.frontline.api.ticket;
import java.time.Instant;
import java.util.UUID;
public record TicketChangedEvent(UUID matchId, Instant occurredAt, long revision,
                                 TicketChange change) implements TicketEvent {}
