package com.warsim.frontline.api.ticket;
import com.warsim.frontline.api.roster.TeamSide;
import java.time.Instant;
import java.util.UUID;
public record TicketsDepletedEvent(UUID matchId, Instant occurredAt, long revision,
                                   TeamSide teamSide) implements TicketEvent {}
