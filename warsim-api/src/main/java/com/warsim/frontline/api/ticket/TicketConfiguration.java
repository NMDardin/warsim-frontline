package com.warsim.frontline.api.ticket;

public record TicketConfiguration(
    boolean enabled,
    TicketSideConfiguration attackers,
    TicketSideConfiguration defenders,
    boolean endMatchOnAttackersDepleted
) {
    public TicketConfiguration {
        if (attackers == null || defenders == null) throw new NullPointerException();
    }

    public static TicketConfiguration defaults(boolean enabled) {
        return enabled
            ? new TicketConfiguration(true, new TicketSideConfiguration(true, 300, 500),
                new TicketSideConfiguration(false, 0, 0), true)
            : disabled();
    }

    public static TicketConfiguration disabled() {
        return new TicketConfiguration(false, new TicketSideConfiguration(false, 0, 0),
            new TicketSideConfiguration(false, 0, 0), false);
    }
}
