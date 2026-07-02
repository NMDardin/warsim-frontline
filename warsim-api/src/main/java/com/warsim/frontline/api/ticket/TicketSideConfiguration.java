package com.warsim.frontline.api.ticket;

public record TicketSideConfiguration(boolean enabled, int initial, int maximum) {
    public TicketSideConfiguration {
        if (initial < 0 || maximum < 0 || initial > maximum) {
            throw new IllegalArgumentException("Invalid ticket side configuration");
        }
        if (!enabled && (initial != 0 || maximum != 0)) {
            throw new IllegalArgumentException("Disabled ticket pool must be zero");
        }
    }
}
