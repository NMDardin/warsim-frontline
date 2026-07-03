package com.warsim.frontline.api.ticket;

public record TicketOperationResult(
    boolean successful,
    boolean duplicate,
    String message,
    TicketSnapshot snapshot,
    TicketChange change
) {
    public static TicketOperationResult rejected(String message, TicketSnapshot snapshot) {
        return new TicketOperationResult(false, false, message, snapshot, null);
    }

    public TicketOperationResult asDuplicate() {
        return new TicketOperationResult(successful, true, message, snapshot, change);
    }
}
