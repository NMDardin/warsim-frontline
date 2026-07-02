package com.warsim.frontline.match.objective;

import com.warsim.frontline.api.roster.TeamSide;
import com.warsim.frontline.api.ticket.*;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class DefaultTicketService implements TicketService {
    private static final int DEDUPLICATION_LIMIT = 1024;

    private final UUID matchId;
    private final TicketConfiguration configuration;
    private final Instant createdAt;
    private final Consumer<RuntimeException> listenerFailureLogger;
    private final List<TicketEventListener> listeners = new ArrayList<>();
    private final ArrayDeque<UUID> operationOrder = new ArrayDeque<>();
    private final Set<UUID> operationIds = new HashSet<>();
    private final Map<UUID, TicketChange> successfulCharges = new HashMap<>();
    private final Set<UUID> refundedCharges = new HashSet<>();
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

    public DefaultTicketService(
        UUID matchId,
        TicketConfiguration configuration,
        Instant createdAt,
        Consumer<RuntimeException> listenerFailureLogger
    ) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.listenerFailureLogger =
            Objects.requireNonNull(listenerFailureLogger, "listenerFailureLogger");
        attackers = configuration.attackers().initial();
        defenders = configuration.defenders().initial();
        attackersDepleted = configuration.attackers().enabled() && attackers == 0;
    }

    @Override
    public synchronized TicketSnapshot snapshot() {
        return new TicketSnapshot(
            matchId,
            pool(TeamSide.ATTACKERS),
            pool(TeamSide.DEFENDERS),
            revision,
            attackersDepleted,
            lastChange,
            createdAt,
            closed
        );
    }

    @Override
    public synchronized TicketOperationResult apply(TicketOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (closed || !configuration.enabled()) {
            rejected++;
            return TicketOperationResult.rejected("票数系统当前不可用", snapshot());
        }
        if (operationIds.contains(operation.operationId())) {
            duplicates++;
            return new TicketOperationResult(
                true, true, "重复操作已忽略", snapshot(), lastChange
            );
        }
        TicketSideConfiguration sideConfiguration = sideConfiguration(operation.teamSide());
        if (!sideConfiguration.enabled()) {
            rejected++;
            return TicketOperationResult.rejected("该阵营未启用有限票数", snapshot());
        }
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
        lastChange = new TicketChange(
            operation.operationId(), matchId, operation.teamSide(), previous, next, delta,
            operation.reason(), operation.occurredAt(), revision
        );
        remember(operation.operationId());
        changes++;
        if (delta > 0) added += delta;
        if (delta < 0) removed += -((long) delta);
        if (operation.reason() == TicketChangeReason.OBJECTIVE_CAPTURE_REWARD) {
            rewards += Math.max(0, delta);
        }
        lastChangedAt = operation.occurredAt();
        publish(new TicketChangedEvent(matchId, operation.occurredAt(), revision, lastChange));
        if (operation.teamSide() == TeamSide.ATTACKERS
            && previous > 0 && next == 0 && !attackersDepleted) {
            attackersDepleted = true;
            depletedEvents++;
            publish(new TicketsDepletedEvent(
                matchId, operation.occurredAt(), revision, TeamSide.ATTACKERS
            ));
        } else if (operation.teamSide() == TeamSide.ATTACKERS && next > 0) {
            attackersDepleted = false;
        }
        return new TicketOperationResult(true, false, "票数操作成功", snapshot(), lastChange);
    }

    @Override
    public synchronized TicketOperationResult tryConsume(TicketOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (operation.type() != TicketOperationType.TAKE) {
            throw new IllegalArgumentException("tryConsume requires TAKE operation");
        }
        if (closed || !configuration.enabled()) {
            rejected++;
            return TicketOperationResult.rejected("票数系统当前不可用", snapshot());
        }
        if (operationIds.contains(operation.operationId())) {
            duplicates++;
            TicketChange previous = successfulCharges.get(operation.operationId());
            return new TicketOperationResult(
                previous != null, true,
                previous == null ? "重复扣票请求已拒绝" : "重复扣票请求已忽略",
                snapshot(), previous
            );
        }
        TicketSideConfiguration sideConfiguration = sideConfiguration(operation.teamSide());
        if (!sideConfiguration.enabled()) {
            rejected++;
            return TicketOperationResult.rejected("该阵营未启用有限票数", snapshot());
        }
        int previous = current(operation.teamSide());
        if (operation.amount() > previous) {
            rejected++;
            remember(operation.operationId());
            return TicketOperationResult.rejected("票数不足，无法部署", snapshot());
        }
        TicketOperationResult result = apply(operation);
        if (result.successful() && result.change() != null) {
            successfulCharges.put(operation.operationId(), result.change());
        }
        return result;
    }

    @Override
    public synchronized TicketOperationResult refund(
        TicketOperation refund, UUID originalOperationId
    ) {
        Objects.requireNonNull(refund, "refund");
        Objects.requireNonNull(originalOperationId, "originalOperationId");
        if (refund.type() != TicketOperationType.ADD) {
            throw new IllegalArgumentException("refund requires ADD operation");
        }
        if (closed || !configuration.enabled()) {
            rejected++;
            return TicketOperationResult.rejected("票数系统当前不可用", snapshot());
        }
        if (operationIds.contains(refund.operationId())) {
            duplicates++;
            return new TicketOperationResult(
                true, true, "重复退款请求已忽略", snapshot(), lastChange
            );
        }
        TicketChange original = successfulCharges.get(originalOperationId);
        if (original == null || refundedCharges.contains(originalOperationId)) {
            rejected++;
            remember(refund.operationId());
            return TicketOperationResult.rejected("没有可退款的重生扣票记录", snapshot());
        }
        TicketOperationResult result = apply(refund);
        if (result.successful()) {
            refundedCharges.add(originalOperationId);
        }
        return result;
    }

    @Override
    public synchronized TicketMetricsSnapshot metrics() {
        return new TicketMetricsSnapshot(
            attackers, changes, added, removed, rewards, rejected, duplicates,
            depletedEvents, lastChangedAt
        );
    }

    @Override
    public synchronized AutoCloseable subscribe(TicketEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> {
            synchronized (DefaultTicketService.this) {
                listeners.remove(listener);
            }
        };
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        listeners.clear();
        operationOrder.clear();
        operationIds.clear();
        successfulCharges.clear();
        refundedCharges.clear();
    }

    private TicketPool pool(TeamSide side) {
        TicketSideConfiguration configured = sideConfiguration(side);
        return new TicketPool(configured.enabled(), current(side), configured.maximum());
    }

    private TicketSideConfiguration sideConfiguration(TeamSide side) {
        return side == TeamSide.ATTACKERS
            ? configuration.attackers() : configuration.defenders();
    }

    private int current(TeamSide side) {
        return side == TeamSide.ATTACKERS ? attackers : defenders;
    }

    private void setCurrent(TeamSide side, int value) {
        if (side == TeamSide.ATTACKERS) attackers = value;
        else defenders = value;
    }

    private void remember(UUID operationId) {
        operationIds.add(operationId);
        operationOrder.addLast(operationId);
        while (operationOrder.size() > DEDUPLICATION_LIMIT) {
            operationIds.remove(operationOrder.removeFirst());
        }
    }

    private void publish(TicketEvent event) {
        for (TicketEventListener listener : List.copyOf(listeners)) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                listenerFailureLogger.accept(exception);
            }
        }
    }
}
