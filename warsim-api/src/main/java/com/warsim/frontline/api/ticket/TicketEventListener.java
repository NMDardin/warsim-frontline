package com.warsim.frontline.api.ticket;
@FunctionalInterface
public interface TicketEventListener { void onEvent(TicketEvent event); }
