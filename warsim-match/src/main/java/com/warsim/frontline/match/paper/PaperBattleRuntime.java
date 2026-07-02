package com.warsim.frontline.match.paper;

import com.warsim.frontline.api.battle.*;
import com.warsim.frontline.api.roster.CombatRelation;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Read-only service bridge exported to independently deployed battle extensions.
 */
public final class PaperBattleRuntime implements WarSimBattleRuntime, AutoCloseable {
    private final CopyOnWriteArrayList<BattleRuntimeListener> listeners =
        new CopyOnWriteArrayList<>();
    private final Consumer<RuntimeException> failureLogger;
    private volatile PaperMatchCoordinator coordinator;
    private volatile boolean closed;

    public PaperBattleRuntime(Consumer<RuntimeException> failureLogger) {
        this.failureLogger = Objects.requireNonNull(failureLogger, "failureLogger");
    }

    public void attach(PaperMatchCoordinator coordinator) {
        if (closed) {
            throw new IllegalStateException("Battle runtime is closed");
        }
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public void detach(PaperMatchCoordinator expected) {
        if (coordinator == expected) {
            coordinator = null;
        }
    }

    @Override
    public BattleRuntimeSnapshot snapshot() {
        PaperMatchCoordinator current = coordinator;
        return closed || current == null
            ? BattleRuntimeSnapshot.unavailable()
            : current.battleSnapshot();
    }

    @Override
    public Optional<BattlePlayerSnapshot> player(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        PaperMatchCoordinator current = coordinator;
        return closed || current == null ? Optional.empty() : current.battlePlayer(playerUuid);
    }

    @Override
    public CombatRelation relation(UUID first, UUID second) {
        PaperMatchCoordinator current = coordinator;
        return closed || current == null
            ? CombatRelation.UNKNOWN : current.combatRelation(first, second);
    }

    @Override
    public AutoCloseable subscribe(BattleRuntimeListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed) {
            throw new IllegalStateException("Battle runtime is closed");
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void publish(BattleRuntimeEvent event) {
        if (closed) return;
        for (BattleRuntimeListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                failureLogger.accept(exception);
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        publish(new BattleRuntimeClosedEvent(Instant.now()));
        closed = true;
        coordinator = null;
        listeners.clear();
    }
}
