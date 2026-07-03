package com.warsim.frontline.match.objective;

import com.warsim.frontline.api.roster.TeamSide;
import com.warsim.frontline.api.ticket.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class DefaultTicketService implements TicketService {
    private static final int TICKET_LEDGER_LIMIT = 4096;

    private final UUID matchId;
    private final TicketConfiguration configuration;
    private final Instant createdAt;
    private final Consumer<RuntimeException> listenerFailureLogger;
    private final List<TicketEventListener> listeners = new ArrayList<>();
    private final LinkedHashMap<UUID, LedgerEntry> ledger = new LinkedHashMap<>();
    private int attackers;
    private int defenders;
    private long revision;
    private boolean attackersDepleted;
    private boolean closed;
    private TicketChange lastChange;
    private long changes;
    private long added;
    private long removed;
    private long rewards;
    private long rejected;
    private long duplicates;
    private long depletedEvents;
    private Instant lastChangedAt;

    public DefaultTicketService(UUID matchId, TicketConfiguration configuration, Instant createdAt, Consumer<RuntimeException> listenerFailureLogger) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.listenerFailureLogger = Objects.requireNonNull(listenerFailureLogger, "listenerFailureLogger");
        attackers = configuration.attackers().initial();
        defenders = configuration.defenders().initial();
        attackersDepleted = configuration.attackers().enabled() && attackers == 0;
    }

    @Override public synchronized TicketSnapshot snapshot() {
        return new TicketSnapshot(matchId, pool(TeamSide.ATTACKERS), pool(TeamSide.DEFENDERS), revision, attackersDepleted, lastChange, createdAt, closed);
    }

    @Override public synchronized TicketOperationResult apply(TicketOperation operation) {
        Objects.requireNonNull(operation, "operation");
        LedgerEntry existing = ledger.get(operation.operationId());
        if (existing != null) { duplicates++; return existing.result().asDuplicate(); }
        if (!canRecordNewOperation()) return ledgerFull();
        if (closed || !configuration.enabled()) {
            TicketOperationResult result = TicketOperationResult.rejected("Ticket system is unavailable", snapshot());
            remember(operation, LedgerType.GENERAL, result, null);
            return result;
        }
        TicketSideConfiguration sideConfiguration = sideConfiguration(operation.teamSide());
        if (!sideConfiguration.enabled()) {
            rejected++;
            TicketOperationResult result = TicketOperationResult.rejected("Ticket side is disabled", snapshot());
            remember(operation, LedgerType.GENERAL, result, null);
            return result;
        }
        TicketOperationResult result = applyUnchecked(operation, sideConfiguration);
        remember(operation, LedgerType.GENERAL, result, null);
        return result;
    }

    @Override public synchronized TicketOperationResult tryConsume(TicketOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (operation.type() != TicketOperationType.TAKE) throw new IllegalArgumentException("tryConsume requires TAKE operation");
        LedgerEntry existing = ledger.get(operation.operationId());
        if (existing != null) { duplicates++; return existing.result().asDuplicate(); }
        if (!canRecordNewOperation()) return ledgerFull();
        if (closed || !configuration.enabled()) {
            TicketOperationResult result = TicketOperationResult.rejected("Ticket system is unavailable", snapshot());
            remember(operation, LedgerType.CHARGE, result, null);
            return result;
        }
        TicketSideConfiguration sideConfiguration = sideConfiguration(operation.teamSide());
        if (!sideConfiguration.enabled()) {
            rejected++;
            TicketOperationResult result = TicketOperationResult.rejected("Ticket side is disabled", snapshot());
            remember(operation, LedgerType.CHARGE, result, null);
            return result;
        }
        int previous = current(operation.teamSide());
        if (operation.amount() > previous) {
            rejected++;
            TicketOperationResult result = TicketOperationResult.rejected("Insufficient tickets", snapshot());
            remember(operation, LedgerType.CHARGE, result, null);
            return result;
        }
        TicketOperationResult result = applyUnchecked(operation, sideConfiguration);
        remember(operation, LedgerType.CHARGE, result, null);
        return result;
    }

    @Override public synchronized TicketOperationResult refund(TicketOperation refund, UUID originalOperationId) {
        Objects.requireNonNull(refund, "refund");
        Objects.requireNonNull(originalOperationId, "originalOperationId");
        if (refund.type() != TicketOperationType.ADD) throw new IllegalArgumentException("refund requires ADD operation");
        LedgerEntry existing = ledger.get(refund.operationId());
        if (existing != null) { duplicates++; return existing.result().asDuplicate(); }
        if (!canRecordNewOperation()) return ledgerFull();
        if (closed || !configuration.enabled()) {
            TicketOperationResult result = TicketOperationResult.rejected("Ticket system is unavailable", snapshot());
            remember(refund, LedgerType.REFUND, result, originalOperationId);
            return result;
        }
        LedgerEntry original = ledger.get(originalOperationId);
        if (original == null || original.operationType() != TicketOperationType.TAKE || !original.successfulCharge()
            || original.refunded() || original.change() == null || original.change().appliedDelta() >= 0) {
            rejected++;
            TicketOperationResult result = TicketOperationResult.rejected("No refundable ticket charge exists", snapshot());
            remember(refund, LedgerType.REFUND, result, originalOperationId);
            return result;
        }
        TicketChange originalChange = original.change();
        int refundAmount = Math.abs(originalChange.appliedDelta());
        if (refund.teamSide() != originalChange.teamSide() || refund.amount() != refundAmount || refund.reason() != TicketChangeReason.RESPAWN_REFUND) {
            rejected++;
            TicketOperationResult result = TicketOperationResult.rejected("Refund request does not match original charge", snapshot());
            remember(refund, LedgerType.REFUND, result, originalOperationId);
            return result;
        }
        TicketOperationResult result = applyUnchecked(refund, sideConfiguration(originalChange.teamSide()));
        if (result.successful()) ledger.put(originalOperationId, original.markRefunded(refund.operationId()));
        remember(refund, LedgerType.REFUND, result, originalOperationId);
        return result;
    }

    @Override public synchronized TicketMetricsSnapshot metrics() {
        return new TicketMetricsSnapshot(attackers, changes, added, removed, rewards, rejected, duplicates, depletedEvents, lastChangedAt);
    }

    @Override public synchronized AutoCloseable subscribe(TicketEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> { synchronized (DefaultTicketService.this) { listeners.remove(listener); } };
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        listeners.clear();
        ledger.clear();
    }

    private TicketOperationResult applyUnchecked(TicketOperation operation, TicketSideConfiguration sideConfiguration) {
        int previous = current(operation.teamSide());
        long candidate = switch (operation.type()) {
            case SET -> operation.amount();
            case ADD -> (long) previous + operation.amount();
            case TAKE -> (long) previous - operation.amount();
        };
        int next = (int) Math.max(0, Math.min(sideConfiguration.maximum(), candidate));
        setCurrent(operation.teamSide(), next);
        revision++;
        int delta = next - previous;
        lastChange = new TicketChange(operation.operationId(), matchId, operation.teamSide(), previous, next, delta, operation.reason(), operation.occurredAt(), revision);
        changes++;
        if (delta > 0) added += delta;
        if (delta < 0) removed += -((long) delta);
        if (operation.reason() == TicketChangeReason.OBJECTIVE_CAPTURE_REWARD) rewards += Math.max(0, delta);
        lastChangedAt = operation.occurredAt();
        publish(new TicketChangedEvent(matchId, operation.occurredAt(), revision, lastChange));
        if (operation.teamSide() == TeamSide.ATTACKERS && previous > 0 && next == 0 && !attackersDepleted) {
            attackersDepleted = true;
            depletedEvents++;
            publish(new TicketsDepletedEvent(matchId, operation.occurredAt(), revision, TeamSide.ATTACKERS));
        } else if (operation.teamSide() == TeamSide.ATTACKERS && next > 0) {
            attackersDepleted = false;
        }
        return new TicketOperationResult(true, false, "Ticket operation applied", snapshot(), lastChange);
    }

    private boolean canRecordNewOperation() { return ledger.size() < TICKET_LEDGER_LIMIT; }

    private TicketOperationResult ledgerFull() {
        rejected++;
        return TicketOperationResult.rejected("Ticket ledger is full; rejecting new operation", snapshot());
    }

    private void remember(TicketOperation operation, LedgerType ledgerType, TicketOperationResult result, UUID relatedOperationId) {
        boolean successfulCharge = operation.type() == TicketOperationType.TAKE && result.successful() && result.change() != null && result.change().appliedDelta() < 0;
        ledger.put(operation.operationId(), new LedgerEntry(operation.operationId(), ledgerType, operation.type(), operation.teamSide(), operation.amount(), result, result.change(), successfulCharge, false, null, relatedOperationId));
    }

    private TicketPool pool(TeamSide side) {
        TicketSideConfiguration configured = sideConfiguration(side);
        return new TicketPool(configured.enabled(), current(side), configured.maximum());
    }

    private TicketSideConfiguration sideConfiguration(TeamSide side) { return side == TeamSide.ATTACKERS ? configuration.attackers() : configuration.defenders(); }
    private int current(TeamSide side) { return side == TeamSide.ATTACKERS ? attackers : defenders; }
    private void setCurrent(TeamSide side, int value) { if (side == TeamSide.ATTACKERS) attackers = value; else defenders = value; }

    private void publish(TicketEvent event) {
        for (TicketEventListener listener : List.copyOf(listeners)) {
            try { listener.onEvent(event); } catch (RuntimeException exception) { listenerFailureLogger.accept(exception); }
        }
    }

    private enum LedgerType { GENERAL, CHARGE, REFUND }

    private record LedgerEntry(
        UUID operationId,
        LedgerType ledgerType,
        TicketOperationType operationType,
        TeamSide teamSide,
        int requestedAmount,
        TicketOperationResult result,
        TicketChange change,
        boolean successfulCharge,
        boolean refunded,
        UUID refundOperationId,
        UUID relatedOperationId
    ) {
        private LedgerEntry markRefunded(UUID refundOperationId) {
            return new LedgerEntry(operationId, ledgerType, operationType, teamSide, requestedAmount, result, change, successfulCharge, true, refundOperationId, relatedOperationId);
        }
    }
}