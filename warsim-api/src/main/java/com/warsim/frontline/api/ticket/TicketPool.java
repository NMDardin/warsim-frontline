package com.warsim.frontline.api.ticket;

public record TicketPool(boolean enabled, int current, int maximum) {
    public TicketPool {
        if (current < 0 || maximum < 0 || current > maximum) {
            throw new IllegalArgumentException("Invalid ticket pool");
        }
    }
}
