package com.warsim.frontline.api.ticket;

public interface TicketService extends AutoCloseable {
    TicketSnapshot snapshot();
    TicketOperationResult apply(TicketOperation operation);
    default TicketOperationResult tryConsume(TicketOperation operation) {
        if (operation.type() != TicketOperationType.TAKE) {
            throw new IllegalArgumentException("tryConsume requires TAKE operation");
        }
        return apply(operation);
    }
    default TicketOperationResult refund(TicketOperation refund, java.util.UUID originalOperationId) {
        if (refund.type() != TicketOperationType.ADD) {
            throw new IllegalArgumentException("refund requires ADD operation");
        }
        java.util.Objects.requireNonNull(originalOperationId, "originalOperationId");
        return apply(refund);
    }
    TicketMetricsSnapshot metrics();
    AutoCloseable subscribe(TicketEventListener listener);
    @Override void close();
}
