package com.warsim.frontline.match.objective;

import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.objective.*;
import com.warsim.frontline.api.roster.TeamSide;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class DefaultObjectiveService implements ObjectiveService {
    private static final double EPSILON = 1.0e-9;
    private static final long MAX_DELTA_NANOS = 1_000_000_000L;

    private final UUID matchId;
    private final ObjectiveConfiguration configuration;
    private final Consumer<RuntimeException> listenerFailureLogger;
    private final Map<ObjectiveId, MutableObjective> objectives = new LinkedHashMap<>();
    private final List<ObjectiveEventListener> listeners = new ArrayList<>();
    private ObjectiveSystemState systemState;
    private long lifecycleRevision;
    private long lastMonotonicNanos = Long.MIN_VALUE;
    private long scanCycles;
    private long scannedPlayers;
    private long contestedTransitions;
    private long neutralizations;
    private long capturesByAttackers;
    private long capturesByDefenders;
    private long staleFrames;
    private long duplicateCaptures;
    private long invalidFrames;
    private long listenerFailures;
    private long displayUpdates;
    private long displayRemovals;
    private long lastScanNanos;
    private long maximumScanNanos;
    private Instant lastCaptureAt;

    public DefaultObjectiveService(
        UUID matchId,
        long lifecycleRevision,
        ObjectiveConfiguration configuration,
        Instant createdAt,
        Consumer<RuntimeException> listenerFailureLogger
    ) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.lifecycleRevision = lifecycleRevision;
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.listenerFailureLogger =
            Objects.requireNonNull(listenerFailureLogger, "listenerFailureLogger");
        systemState = configuration.enabled()
            ? ObjectiveSystemState.CREATED : ObjectiveSystemState.DISABLED;
        for (ObjectiveDefinition definition : configuration.definitions()) {
            MutableObjective objective = new MutableObjective(definition, createdAt);
            objectives.put(definition.objectiveId(), objective);
            publish(new ObjectiveCreatedEvent(
                matchId, definition.objectiveId(), createdAt, objective.revision
            ));
        }
        if (configuration.enabled()) systemState = ObjectiveSystemState.ACTIVE;
    }

    @Override
    public synchronized ObjectiveSystemState systemState() {
        return systemState;
    }

    @Override
    public synchronized List<ObjectiveSnapshot> snapshots() {
        return objectives.values().stream().map(this::snapshot).toList();
    }

    @Override
    public synchronized ObjectiveSnapshot snapshot(ObjectiveId objectiveId) {
        return snapshot(require(objectiveId));
    }

    @Override
    public synchronized void synchronizeLifecycle(long lifecycleRevision) {
        this.lifecycleRevision = lifecycleRevision;
    }

    @Override
    public synchronized boolean process(ObjectivePresenceFrame frame, MatchState matchState) {
        long started = System.nanoTime();
        try {
            if (systemState != ObjectiveSystemState.ACTIVE) return false;
            if (!matchId.equals(frame.matchId())
                || lifecycleRevision != frame.lifecycleRevision()) {
                staleFrames++;
                return false;
            }
            if (matchState != MatchState.PLAYING) {
                lastMonotonicNanos = frame.monotonicNanos();
                return true;
            }
            if (frame.monotonicNanos() < 0
                || (lastMonotonicNanos != Long.MIN_VALUE
                    && frame.monotonicNanos() < lastMonotonicNanos)) {
                invalidFrames++;
                return false;
            }
            long deltaNanos = lastMonotonicNanos == Long.MIN_VALUE
                ? 0 : Math.min(MAX_DELTA_NANOS, frame.monotonicNanos() - lastMonotonicNanos);
            lastMonotonicNanos = frame.monotonicNanos();
            scanCycles++;
            scannedPlayers += frame.players().size();
            for (MutableObjective objective : objectives.values()) {
                ObjectivePresence presence = count(objective.definition.region(), frame.players());
                update(objective, presence, deltaNanos, frame.sampledAt());
            }
            return true;
        } finally {
            lastScanNanos = Math.max(0, System.nanoTime() - started);
            maximumScanNanos = Math.max(maximumScanNanos, lastScanNanos);
        }
    }

    @Override
    public synchronized ObjectiveOperationResult lock(ObjectiveId id, Instant now) {
        MutableObjective objective = require(id);
        objective.locked = true;
        changed(objective, ObjectiveState.LOCKED, now);
        return ObjectiveOperationResult.success("据点已锁定", snapshot(objective));
    }

    @Override
    public synchronized ObjectiveOperationResult unlock(ObjectiveId id, Instant now) {
        MutableObjective objective = require(id);
        objective.locked = false;
        changed(objective, stableState(objective), now);
        publish(new ObjectiveUnlockedEvent(matchId, id, now, objective.revision));
        return ObjectiveOperationResult.success("据点已解锁", snapshot(objective));
    }

    @Override
    public synchronized ObjectiveOperationResult reset(ObjectiveId id, Instant now) {
        MutableObjective objective = require(id);
        objective.reset(now);
        publish(new ObjectiveResetEvent(matchId, id, now, objective.revision));
        return ObjectiveOperationResult.success("据点已恢复初始状态", snapshot(objective));
    }

    @Override
    public synchronized ObjectiveOperationResult setOwner(
        ObjectiveId id, ObjectiveOwner owner, Instant now
    ) {
        MutableObjective objective = require(id);
        objective.owner = Objects.requireNonNull(owner, "owner");
        objective.progress = owner == ObjectiveOwner.NEUTRAL ? 0 : 1;
        objective.progressingSide = null;
        changed(objective, stableState(objective), now);
        return ObjectiveOperationResult.success("据点所有权已更新", snapshot(objective));
    }

    @Override
    public synchronized ObjectiveMetricsSnapshot metrics() {
        return new ObjectiveMetricsSnapshot(
            scanCycles, scannedPlayers, objectives.size(), contestedTransitions,
            neutralizations, capturesByAttackers, capturesByDefenders, staleFrames,
            duplicateCaptures, invalidFrames, displayUpdates, displayRemovals, listenerFailures, lastScanNanos,
            maximumScanNanos, lastCaptureAt
        );
    }

    public synchronized void recordDisplayUpdate() {
        displayUpdates++;
    }

    public synchronized void recordDisplayRemoval() {
        displayRemovals++;
    }

    @Override
    public synchronized AutoCloseable subscribe(ObjectiveEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> {
            synchronized (DefaultObjectiveService.this) {
                listeners.remove(listener);
            }
        };
    }

    @Override
    public synchronized void close() {
        if (systemState == ObjectiveSystemState.CLOSED) return;
        listeners.clear();
        objectives.clear();
        systemState = ObjectiveSystemState.CLOSED;
    }

    private ObjectivePresence count(
        ObjectiveRegion region, List<ObjectivePlayerPresence> players
    ) {
        int attackers = 0;
        int defenders = 0;
        for (ObjectivePlayerPresence player : players) {
            if (!region.contains(player.worldName(), player.x(), player.y(), player.z())) continue;
            if (player.teamSide() == TeamSide.ATTACKERS) attackers++;
            else defenders++;
        }
        return new ObjectivePresence(attackers, defenders);
    }

    private void update(
        MutableObjective objective, ObjectivePresence presence, long deltaNanos, Instant now
    ) {
        objective.attackers = presence.attackersPresent();
        objective.defenders = presence.defendersPresent();
        if (objective.locked) {
            changed(objective, ObjectiveState.LOCKED, now);
            return;
        }
        int attackers = presence.attackersPresent();
        int defenders = presence.defendersPresent();
        if (attackers == defenders && attackers > 0) {
            if (objective.state != ObjectiveState.CONTESTED) {
                changed(objective, ObjectiveState.CONTESTED, now);
                contestedTransitions++;
                publish(new ObjectiveContestedEvent(
                    matchId, objective.definition.objectiveId(), now, objective.revision
                ));
            } else {
                objective.revision++;
            }
            return;
        }
        if (attackers == 0 && defenders == 0) {
            restore(objective, deltaNanos, now);
            return;
        }
        TeamSide dominant = attackers > defenders ? TeamSide.ATTACKERS : TeamSide.DEFENDERS;
        int net = Math.abs(attackers - defenders);
        double work = normalizedWork(objective, deltaNanos, net);
        advance(objective, dominant, work, now);
    }

    private void restore(MutableObjective objective, long deltaNanos, Instant now) {
        double work = normalizedWork(objective, deltaNanos, 1);
        if (objective.owner == ObjectiveOwner.NEUTRAL) {
            objective.progress = clamp(objective.progress - work);
            if (objective.progress <= EPSILON) {
                objective.progress = 0;
                objective.progressingSide = null;
                changed(objective, ObjectiveState.IDLE, now);
            } else {
                changed(objective, ObjectiveState.CAPTURING, now);
            }
        } else {
            objective.progress = clamp(objective.progress + work);
            objective.progressingSide = objective.owner.teamSide();
            if (objective.progress >= 1 - EPSILON) {
                objective.progress = 1;
                objective.progressingSide = null;
                changed(objective, ObjectiveState.CONTROLLED, now);
            } else {
                changed(objective, ObjectiveState.CONTROLLED, now);
            }
        }
    }

    private void advance(
        MutableObjective objective, TeamSide dominant, double work, Instant now
    ) {
        if (work <= 0) {
            objective.progressingSide = dominant;
            changed(objective,
                objective.owner == ObjectiveOwner.from(dominant)
                    ? ObjectiveState.CONTROLLED
                    : objective.owner == ObjectiveOwner.NEUTRAL
                        ? ObjectiveState.CAPTURING : ObjectiveState.NEUTRALIZING,
                now);
            return;
        }
        ObjectiveOwner dominantOwner = ObjectiveOwner.from(dominant);
        if (objective.owner == dominantOwner) {
            objective.progress = clamp(objective.progress + work);
            objective.progressingSide = objective.progress >= 1 - EPSILON ? null : dominant;
            if (objective.progress >= 1 - EPSILON) objective.progress = 1;
            changed(objective, ObjectiveState.CONTROLLED, now);
            return;
        }
        if (objective.owner != ObjectiveOwner.NEUTRAL) {
            double consumed = Math.min(work, objective.progress);
            objective.progress = clamp(objective.progress - consumed);
            objective.progressingSide = dominant;
            work -= consumed;
            if (objective.progress <= EPSILON) {
                objective.progress = 0;
                objective.owner = ObjectiveOwner.NEUTRAL;
                objective.revision++;
                objective.stateChangedAt = now;
                neutralizations++;
                publish(new ObjectiveNeutralizedEvent(
                    matchId, objective.definition.objectiveId(), now,
                    objective.revision, dominant
                ));
            } else {
                changed(objective, ObjectiveState.NEUTRALIZING, now);
                return;
            }
        }
        if (objective.progressingSide != null && objective.progressingSide != dominant) {
            double consumed = Math.min(work, objective.progress);
            objective.progress = clamp(objective.progress - consumed);
            work -= consumed;
            if (objective.progress <= EPSILON) {
                objective.progress = 0;
                objective.progressingSide = dominant;
            }
        } else {
            objective.progressingSide = dominant;
        }
        if (work > 0) objective.progress = clamp(objective.progress + work);
        if (objective.progress >= 1 - EPSILON) {
            objective.progress = 1;
            ObjectiveOwner previous = objective.owner;
            objective.owner = dominantOwner;
            objective.progressingSide = null;
            changed(objective, ObjectiveState.CONTROLLED, now);
            if (previous != dominantOwner) capture(objective, dominant, now);
        } else {
            changed(objective, ObjectiveState.CAPTURING, now);
        }
    }

    private void capture(MutableObjective objective, TeamSide side, Instant now) {
        String key = side + ":" + objective.revision;
        if (key.equals(objective.lastCaptureKey)) {
            duplicateCaptures++;
            return;
        }
        objective.lastCaptureKey = key;
        if (side == TeamSide.ATTACKERS) capturesByAttackers++;
        else capturesByDefenders++;
        lastCaptureAt = now;
        publish(new ObjectiveCapturedEvent(
            matchId, objective.definition.objectiveId(), now, objective.revision,
            side, objective.definition.rewards().forSide(side)
        ));
    }

    private double normalizedWork(MutableObjective objective, long nanos, int netPlayers) {
        return (nanos / 1_000_000_000.0)
            / objective.definition.captureRules().baseSeconds()
            * objective.definition.captureRules().multiplier(netPlayers);
    }

    private void changed(MutableObjective objective, ObjectiveState state, Instant now) {
        if (objective.state != state) objective.stateChangedAt = now;
        objective.state = state;
        objective.revision++;
    }

    private ObjectiveState stableState(MutableObjective objective) {
        if (objective.locked) return ObjectiveState.LOCKED;
        return objective.owner == ObjectiveOwner.NEUTRAL
            ? ObjectiveState.IDLE : ObjectiveState.CONTROLLED;
    }

    private MutableObjective require(ObjectiveId id) {
        MutableObjective objective = objectives.get(id);
        if (objective == null) throw new IllegalArgumentException("Unknown objective: " + id);
        return objective;
    }

    private ObjectiveSnapshot snapshot(MutableObjective objective) {
        return new ObjectiveSnapshot(
            matchId, objective.definition.objectiveId(), objective.definition.displayName(),
            objective.owner, objective.state, objective.progress, objective.progressingSide,
            objective.attackers, objective.defenders, objective.locked,
            objective.stateChangedAt, objective.revision
        );
    }

    private void publish(ObjectiveEvent event) {
        for (ObjectiveEventListener listener : List.copyOf(listeners)) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                listenerFailures++;
                listenerFailureLogger.accept(exception);
            }
        }
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static final class MutableObjective {
        private final ObjectiveDefinition definition;
        private ObjectiveOwner owner;
        private ObjectiveState state;
        private double progress;
        private TeamSide progressingSide;
        private int attackers;
        private int defenders;
        private boolean locked;
        private Instant stateChangedAt;
        private long revision;
        private String lastCaptureKey;

        private MutableObjective(ObjectiveDefinition definition, Instant now) {
            this.definition = definition;
            reset(now);
        }

        private void reset(Instant now) {
            owner = definition.initialOwner();
            progress = owner == ObjectiveOwner.NEUTRAL ? 0 : 1;
            progressingSide = null;
            attackers = 0;
            defenders = 0;
            locked = definition.initiallyLocked();
            state = locked ? ObjectiveState.LOCKED
                : owner == ObjectiveOwner.NEUTRAL
                    ? ObjectiveState.IDLE : ObjectiveState.CONTROLLED;
            stateChangedAt = now;
            revision++;
            lastCaptureKey = null;
        }
    }
}
