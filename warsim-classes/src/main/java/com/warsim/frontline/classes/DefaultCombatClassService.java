package com.warsim.frontline.classes;

import com.warsim.frontline.api.classes.*;
import com.warsim.frontline.api.roster.TeamSide;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class DefaultCombatClassService implements CombatClassService {
    private final CombatClassConfiguration configuration;
    private final Map<CombatClassId, CombatClassDefinition> definitions;
    private final Map<UUID, PreferredClassSelection> preferences = new LinkedHashMap<>();
    private final Map<UUID, PlayerClassSelection> selections = new LinkedHashMap<>();
    private final Map<UUID, DeploymentContext> deployments = new LinkedHashMap<>();
    private final List<ClassDeploymentEventListener> listeners = new ArrayList<>();
    private final Consumer<RuntimeException> listenerFailureLogger;
    private final MutableClassDeploymentMetrics metrics = new MutableClassDeploymentMetrics();
    private final UUID matchId;
    private long lifecycleRevision;
    private ClassSubsystemState classState;
    private DeploymentSubsystemState deploymentState;
    private String lastError;
    private boolean closed;

    public DefaultCombatClassService(UUID matchId, long lifecycleRevision, CombatClassConfiguration configuration, Consumer<RuntimeException> listenerFailureLogger) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.lifecycleRevision = lifecycleRevision;
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.definitions = configuration.byId();
        this.listenerFailureLogger = Objects.requireNonNull(listenerFailureLogger, "listenerFailureLogger");
        this.classState = configuration.enabled() ? ClassSubsystemState.ACTIVE : ClassSubsystemState.DISABLED;
        this.deploymentState = DeploymentSubsystemState.DISABLED;
    }

    public synchronized void updateLifecycle(UUID expectedMatchId, long revision) { if (!closed && matchId.equals(expectedMatchId)) lifecycleRevision = revision; }

    public synchronized void setDeploymentState(DeploymentSubsystemState state, String error) {
        if (closed) return;
        deploymentState = Objects.requireNonNull(state, "state");
        lastError = error;
        if (state == DeploymentSubsystemState.WAITING_PROVIDER) metrics.providerUnavailableRejections.incrementAndGet();
    }

    public MutableClassDeploymentMetrics mutableMetrics() { return metrics; }

    @Override public synchronized ClassDeploymentSnapshot snapshot() {
        return new ClassDeploymentSnapshot(classState, deploymentState, matchId, lifecycleRevision,
            configuration.configurationRevision(), List.copyOf(selections.values()), metrics.snapshot(), Optional.ofNullable(lastError));
    }

    @Override public List<CombatClassDefinition> definitions() { return List.copyOf(definitions.values()); }
    @Override public synchronized Optional<PlayerClassSelection> selection(UUID playerUuid) { return Optional.ofNullable(selections.get(playerUuid)); }

    @Override
    public synchronized DeploymentResult selectClass(UUID playerUuid, UUID expectedMatchId, CombatClassId classId, Instant now) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(classId, "classId");
        if (!canUseClasses()) return rejected(DeploymentFailureReason.DISABLED, "Class system is unavailable", null, now);
        if (!definitions.containsKey(classId)) return rejected(DeploymentFailureReason.NO_CLASS_SELECTED, "Unknown class", null, now);
        preferences.put(playerUuid, new PreferredClassSelection(playerUuid, classId, now));
        PlayerClassSelection current = selectionOrCreate(playerUuid, expectedMatchId);
        boolean pendingOnly = current.combatState() == PlayerCombatState.ALIVE || current.teamSide().isEmpty();
        if (pendingOnly) {
            selections.put(playerUuid, replace(current, current.currentClass(), Optional.of(classId), current.combatState()));
        } else {
            DeploymentResult applied = applyCurrentClass(current, classId, now);
            if (!applied.successful()) return applied;
        }
        publish(new ClassSelectedEvent(playerUuid, matchId, classId, pendingOnly, now));
        return new DeploymentResult(true, DeploymentFailureReason.NONE, DeploymentTransactionStage.VALIDATED,
            pendingOnly ? "Class preference recorded" : "Class selected", null);
    }

    @Override
    public synchronized DeploymentResult clearClass(UUID playerUuid, UUID expectedMatchId, Instant now) {
        PlayerClassSelection current = selections.get(playerUuid);
        if (current == null || !current.matchId().equals(expectedMatchId)) return rejected(DeploymentFailureReason.INVALID_MATCH, "No current match class state", null, now);
        deployments.remove(playerUuid);
        preferences.remove(playerUuid);
        PlayerCombatState nextState = current.combatState() == PlayerCombatState.ALIVE
            ? PlayerCombatState.WAITING_DEPLOYMENT
            : current.combatState() == PlayerCombatState.CLOSED ? PlayerCombatState.CLOSED : PlayerCombatState.NOT_DEPLOYED;
        selections.put(playerUuid, replace(current, Optional.empty(), Optional.empty(), nextState));
        return new DeploymentResult(true, DeploymentFailureReason.NONE, DeploymentTransactionStage.VALIDATED, "Class state cleared", null);
    }

    @Override
    public synchronized DeploymentResult startDeployment(DeploymentRequest request, long nowMonotonic, Instant now) {
        Objects.requireNonNull(request, "request");
        PlayerClassSelection current = selectionOrCreate(request.playerUuid(), request.matchId());
        if (deployments.containsKey(request.playerUuid())) return rejected(DeploymentFailureReason.ALREADY_DEPLOYING, "Deployment countdown is already active", null, now);
        if (current.combatState() == PlayerCombatState.ALIVE || current.combatState() == PlayerCombatState.DEPLOYING || current.combatState() == PlayerCombatState.CLOSED) {
            return rejected(DeploymentFailureReason.INVALID_STATE, "Current state cannot start deployment", null, now);
        }
        if (request.reason() != current.nextDeploymentReason()) return rejected(DeploymentFailureReason.INVALID_STATE, "Deployment reason does not match current life", null, now);
        if (current.currentClass().isEmpty()) return rejected(DeploymentFailureReason.NO_CLASS_SELECTED, "Select a class first", null, now);
        if (current.teamSide().filter(request.teamSide()::equals).isEmpty() || current.currentClass().filter(request.requestedClass()::equals).isEmpty()) {
            return rejected(DeploymentFailureReason.INVALID_STATE, "Reserved class/team does not match request", null, now);
        }
        DeploymentResult capacity = validateReservedCapacity(current, request.requestedClass(), now);
        if (!capacity.successful()) return capacity;
        long deploymentRevision = current.deploymentRevision() + 1;
        DeploymentContext context = new DeploymentContext(request.playerUuid(), request.matchId(), request.lifecycleRevision(), deploymentRevision,
            current.lifeRevision(), current.lifeRevision() + 1, current.currentClass().orElseThrow(), request.teamSide(), request.reason(), request.trigger(),
            request.spawnType(), request.spawnId().orElse("team_fixed"), nowMonotonic, nowMonotonic + request.delayNanos(), configuration.configurationRevision(), DeploymentTransactionStage.CREATED);
        selections.put(request.playerUuid(), new PlayerClassSelection(current.playerUuid(), current.matchId(), current.currentClass(), current.pendingClass(), current.teamSide(), PlayerCombatState.DEPLOYING, current.successfulDeploymentCount(), current.lifeRevision(), deploymentRevision));
        deployments.put(request.playerUuid(), context);
        publish(new DeploymentStartedEvent(request.playerUuid(), request.matchId(), deploymentRevision, current.lifeRevision(), current.lifeRevision() + 1, context.requestedClass(), request.reason(), request.trigger(), now));
        return new DeploymentResult(true, DeploymentFailureReason.NONE, DeploymentTransactionStage.CREATED, "Deployment countdown started", context);
    }

    @Override public synchronized Optional<DeploymentContext> activeDeployment(UUID playerUuid) { return Optional.ofNullable(deployments.get(playerUuid)); }

    @Override
    public synchronized DeploymentResult cancelDeployment(UUID playerUuid, String reason, Instant now) {
        DeploymentContext context = deployments.remove(playerUuid);
        PlayerClassSelection current = selections.get(playerUuid);
        if (current != null && current.combatState() == PlayerCombatState.DEPLOYING) selections.put(playerUuid, replace(current, current.currentClass(), current.pendingClass(), PlayerCombatState.WAITING_DEPLOYMENT));
        if (context == null) return rejected(DeploymentFailureReason.INVALID_MATCH, "No active deployment", null, now);
        return new DeploymentResult(true, DeploymentFailureReason.NONE, DeploymentTransactionStage.ROLLED_BACK, reason, context);
    }

    @Override
    public synchronized DeploymentResult markAlive(DeploymentContext context, Instant now) {
        Objects.requireNonNull(context, "context");
        PlayerClassSelection current = selections.get(context.playerUuid());
        if (current == null || !validContext(context, current)) {
            metrics.staleDeploymentsRejected.incrementAndGet();
            return rejected(DeploymentFailureReason.STALE_CONTEXT, "Deployment context expired", context, now);
        }
        DeploymentResult capacity = revalidateDeploymentCapacity(context, now);
        if (!capacity.successful()) return capacity;
        deployments.remove(context.playerUuid());
        int deployed = current.successfulDeploymentCount() + 1;
        selections.put(context.playerUuid(), new PlayerClassSelection(current.playerUuid(), current.matchId(), Optional.of(context.requestedClass()), Optional.empty(), Optional.of(context.teamSide()), PlayerCombatState.ALIVE, deployed, context.proposedLifeRevision(), context.deploymentRevision()));
        if (context.reason() == DeploymentReason.INITIAL_DEPLOYMENT) metrics.initialDeployments.incrementAndGet(); else metrics.respawnDeployments.incrementAndGet();
        publish(new DeploymentCompletedEvent(context.playerUuid(), context.matchId(), context.deploymentRevision(), context.proposedLifeRevision(), context.requestedClass(), context.reason(), context.trigger(), now));
        return new DeploymentResult(true, DeploymentFailureReason.NONE, DeploymentTransactionStage.COMMITTED, "Deployment committed", context.stage(DeploymentTransactionStage.COMMITTED));
    }

    @Override
    public synchronized DeploymentResult markDead(UUID playerUuid, UUID expectedMatchId, long lifeRevision, Instant now) {
        PlayerClassSelection current = selections.get(playerUuid);
        if (current == null || !current.matchId().equals(expectedMatchId) || current.lifeRevision() != lifeRevision || current.combatState() != PlayerCombatState.ALIVE) {
            return rejected(DeploymentFailureReason.STALE_CONTEXT, "Death event is stale or duplicate", null, now);
        }
        PlayerClassSelection dead = replace(current, current.currentClass(), current.pendingClass(), PlayerCombatState.DEAD);
        selections.put(playerUuid, dead);
        dead.pendingClass().ifPresent(pending -> applyPendingIfPossible(dead, pending, now));
        return new DeploymentResult(true, DeploymentFailureReason.NONE, DeploymentTransactionStage.VALIDATED, "Player marked dead", null);
    }

    @Override
    public synchronized DeploymentResult revalidateDeploymentCapacity(DeploymentContext context, Instant now) {
        Objects.requireNonNull(context, "context");
        PlayerClassSelection current = selections.get(context.playerUuid());
        if (current == null || !validContext(context, current) || current.currentClass().filter(context.requestedClass()::equals).isEmpty() || current.teamSide().filter(context.teamSide()::equals).isEmpty()) {
            metrics.staleDeploymentsRejected.incrementAndGet();
            return rejected(DeploymentFailureReason.STALE_CONTEXT, "Reserved deployment class is stale", context, now);
        }
        return validateReservedCapacity(current, context.requestedClass(), now);
    }

    @Override
    public synchronized DeploymentResult markWaitingDeployment(UUID playerUuid, UUID expectedMatchId, TeamSide teamSide, Instant now) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(teamSide, "teamSide");
        PlayerClassSelection current = selectionOrCreate(playerUuid, expectedMatchId);
        if (current.combatState() == PlayerCombatState.ALIVE || current.combatState() == PlayerCombatState.DEPLOYING || current.combatState() == PlayerCombatState.CLOSED) {
            return rejected(DeploymentFailureReason.INVALID_STATE, "Current state cannot enter waiting deployment", null, now);
        }
        PlayerClassSelection waiting = new PlayerClassSelection(current.playerUuid(), current.matchId(), current.currentClass(), current.pendingClass(), Optional.of(teamSide), PlayerCombatState.WAITING_DEPLOYMENT, current.successfulDeploymentCount(), current.lifeRevision(), current.deploymentRevision());
        selections.put(playerUuid, waiting);
        CombatClassId candidate = waiting.pendingClass().or(() -> waiting.currentClass()).orElse(null);
        if (candidate != null) {
            DeploymentResult reserved = applyPendingIfPossible(waiting, candidate, now);
            if (!reserved.successful() && reserved.failureReason() != DeploymentFailureReason.CLASS_LIMIT_REACHED) return reserved;
        }
        return new DeploymentResult(true, DeploymentFailureReason.NONE, DeploymentTransactionStage.VALIDATED, "Entered waiting deployment", null);
    }

    @Override public synchronized void playerJoined(UUID playerUuid, UUID expectedMatchId, Optional<CombatClassId> preferred, Instant now) {
        if (closed || !matchId.equals(expectedMatchId)) return;
        preferred.ifPresent(id -> preferences.put(playerUuid, new PreferredClassSelection(playerUuid, id, now)));
        selectionOrCreate(playerUuid, expectedMatchId);
    }

    @Override public synchronized void playerDisconnected(UUID playerUuid, Instant now) {
        deployments.remove(playerUuid);
        preferences.remove(playerUuid);
        PlayerClassSelection current = selections.get(playerUuid);
        if (current != null && current.combatState() == PlayerCombatState.DEPLOYING) selections.put(playerUuid, replace(current, current.currentClass(), current.pendingClass(), PlayerCombatState.WAITING_DEPLOYMENT));
    }

    @Override public synchronized void closePlayer(UUID playerUuid, Instant now) {
        deployments.remove(playerUuid);
        PlayerClassSelection current = selections.get(playerUuid);
        if (current != null) selections.put(playerUuid, replace(current, Optional.empty(), Optional.empty(), PlayerCombatState.CLOSED));
        preferences.remove(playerUuid);
    }

    @Override public synchronized Optional<CombatEligibilitySnapshot> eligibility(UUID playerUuid) {
        PlayerClassSelection selection = selections.get(playerUuid);
        if (selection == null) return Optional.empty();
        boolean eligible = selection.combatState() == PlayerCombatState.ALIVE && selection.currentClass().isPresent();
        return Optional.of(new CombatEligibilitySnapshot(playerUuid, selection.matchId(), lifecycleRevision, selection.lifeRevision(), selection.combatState(), eligible));
    }

    @Override public synchronized AutoCloseable subscribe(ClassDeploymentEventListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> { synchronized (DefaultCombatClassService.this) { listeners.remove(listener); } };
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        classState = ClassSubsystemState.CLOSED;
        deploymentState = DeploymentSubsystemState.CLOSED;
        deployments.clear(); selections.clear(); preferences.clear(); listeners.clear();
    }

    public synchronized void resetForNewMatch(UUID newMatchId, long revision, Instant now) {
        deployments.clear(); selections.clear(); preferences.clear();
        if (!matchId.equals(newMatchId)) {
            lastError = "Class service is bound to old matchId; Paper must create a fresh service";
            classState = ClassSubsystemState.DEGRADED;
        }
        lifecycleRevision = revision;
    }

    private boolean canUseClasses() { return !closed && classState == ClassSubsystemState.ACTIVE; }

    private PlayerClassSelection selectionOrCreate(UUID playerUuid, UUID expectedMatchId) {
        if (!matchId.equals(expectedMatchId)) throw new IllegalArgumentException("Class service is bound to a different matchId");
        return selections.computeIfAbsent(playerUuid, ignored -> {
            Optional<CombatClassId> preferred = Optional.ofNullable(preferences.get(playerUuid)).map(PreferredClassSelection::preferredClass).filter(definitions::containsKey);
            return new PlayerClassSelection(playerUuid, matchId, Optional.empty(), preferred, Optional.empty(), PlayerCombatState.NOT_DEPLOYED, 0, 0, 0);
        });
    }

    private DeploymentResult applyPendingIfPossible(PlayerClassSelection current, CombatClassId requested, Instant now) {
        return applyCurrentClass(current, current.pendingClass().orElse(requested), now);
    }

    private DeploymentResult applyCurrentClass(PlayerClassSelection current, CombatClassId target, Instant now) {
        CombatClassDefinition definition = definitions.get(target);
        if (definition == null) return rejected(DeploymentFailureReason.NO_CLASS_SELECTED, "Unknown class", null, now);
        if (current.teamSide().isEmpty()) {
            selections.put(current.playerUuid(), replace(current, current.currentClass(), Optional.of(target), current.combatState()));
            return new DeploymentResult(true, DeploymentFailureReason.NONE, DeploymentTransactionStage.VALIDATED, "Class preference recorded", null);
        }
        int occupied = occupied(target, current.teamSide().orElseThrow(), current.playerUuid());
        if (occupied >= definition.maximumPlayers()) {
            metrics.classLimitRejections.incrementAndGet();
            metrics.pendingClassApplicationFailures.incrementAndGet();
            return rejected(DeploymentFailureReason.CLASS_LIMIT_REACHED, "Class capacity is full", null, now);
        }
        selections.put(current.playerUuid(), replace(current, Optional.of(target), Optional.empty(), current.combatState()));
        metrics.pendingClassApplications.incrementAndGet();
        return new DeploymentResult(true, DeploymentFailureReason.NONE, DeploymentTransactionStage.VALIDATED, "Class reserved", null);
    }

    private DeploymentResult validateReservedCapacity(PlayerClassSelection current, CombatClassId target, Instant now) {
        CombatClassDefinition definition = definitions.get(target);
        if (definition == null || current.teamSide().isEmpty() || current.currentClass().filter(target::equals).isEmpty()) return rejected(DeploymentFailureReason.NO_CLASS_SELECTED, "No valid class reservation", null, now);
        int occupiedExcludingSelf = occupied(target, current.teamSide().orElseThrow(), current.playerUuid());
        if (occupiedExcludingSelf >= definition.maximumPlayers()) {
            metrics.classLimitRejections.incrementAndGet();
            metrics.pendingClassApplicationFailures.incrementAndGet();
            return rejected(DeploymentFailureReason.CLASS_LIMIT_REACHED, "Class capacity is full", null, now);
        }
        return new DeploymentResult(true, DeploymentFailureReason.NONE, DeploymentTransactionStage.VALIDATED, "Class reservation is valid", null);
    }

    private int occupied(CombatClassId target, TeamSide teamSide, UUID excludedPlayerUuid) {
        int count = 0;
        for (PlayerClassSelection selection : selections.values()) {
            if (selection.playerUuid().equals(excludedPlayerUuid)) continue;
            if (selection.teamSide().filter(teamSide::equals).isEmpty()) continue;
            if (selection.currentClass().filter(target::equals).isEmpty()) continue;
            if (selection.combatState() == PlayerCombatState.WAITING_DEPLOYMENT || selection.combatState() == PlayerCombatState.DEPLOYING || selection.combatState() == PlayerCombatState.ALIVE || selection.combatState() == PlayerCombatState.DEAD) count++;
        }
        return count;
    }

    private boolean validContext(DeploymentContext context, PlayerClassSelection current) {
        return current.matchId().equals(context.matchId()) && current.deploymentRevision() == context.deploymentRevision() && current.lifeRevision() == context.currentLifeRevision() && context.classConfigurationRevision() == configuration.configurationRevision() && current.combatState() == PlayerCombatState.DEPLOYING;
    }

    private PlayerClassSelection replace(PlayerClassSelection current, Optional<CombatClassId> currentClass, Optional<CombatClassId> pendingClass, PlayerCombatState state) {
        return new PlayerClassSelection(current.playerUuid(), current.matchId(), currentClass, pendingClass, current.teamSide(), state, current.successfulDeploymentCount(), current.lifeRevision(), current.deploymentRevision());
    }

    private DeploymentResult rejected(DeploymentFailureReason reason, String message, DeploymentContext context, Instant now) {
        publish(new DeploymentFailedEvent(context == null ? new UUID(0, 0) : context.playerUuid(), context == null ? matchId : context.matchId(), context == null ? 0 : context.deploymentRevision(), reason, context == null ? DeploymentTransactionStage.FAILED : context.stage(), message, now));
        return DeploymentResult.rejected(reason, message, context);
    }

    private void publish(ClassDeploymentEvent event) {
        for (ClassDeploymentEventListener listener : List.copyOf(listeners)) {
            try { listener.onEvent(event); } catch (RuntimeException exception) { listenerFailureLogger.accept(exception); }
        }
    }
}
