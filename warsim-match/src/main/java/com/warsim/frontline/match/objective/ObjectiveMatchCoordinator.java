package com.warsim.frontline.match.objective;

import com.warsim.frontline.api.match.*;
import com.warsim.frontline.api.objective.*;
import com.warsim.frontline.api.ticket.*;
import com.warsim.frontline.match.DefaultMatchService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Owns the objective and ticket services for the current match and isolates them from Paper.
 */
public final class ObjectiveMatchCoordinator implements AutoCloseable {
    private final DefaultMatchService match;
    private final ObjectiveConfiguration objectiveConfiguration;
    private final TicketConfiguration ticketConfiguration;
    private final Consumer<RuntimeException> failureLogger;
    private final Consumer<ObjectiveCapturedEvent> captureListener;
    private final AutoCloseable matchSubscription;
    private DefaultObjectiveService objectives;
    private DefaultTicketService tickets;
    private AutoCloseable objectiveSubscription;
    private AutoCloseable sectorSubscription;
    private AutoCloseable ticketSubscription;
    private boolean closed;

    public ObjectiveMatchCoordinator(
        DefaultMatchService match,
        ObjectiveConfiguration objectiveConfiguration,
        TicketConfiguration ticketConfiguration,
        Consumer<RuntimeException> failureLogger
    ) {
        this(match, objectiveConfiguration, ticketConfiguration, failureLogger, ignored -> {});
    }

    public ObjectiveMatchCoordinator(
        DefaultMatchService match,
        ObjectiveConfiguration objectiveConfiguration,
        TicketConfiguration ticketConfiguration,
        Consumer<RuntimeException> failureLogger,
        Consumer<ObjectiveCapturedEvent> captureListener
    ) {
        this.match = Objects.requireNonNull(match, "match");
        this.objectiveConfiguration =
            Objects.requireNonNull(objectiveConfiguration, "objectiveConfiguration");
        this.ticketConfiguration =
            Objects.requireNonNull(ticketConfiguration, "ticketConfiguration");
        this.failureLogger = Objects.requireNonNull(failureLogger, "failureLogger");
        this.captureListener = Objects.requireNonNull(captureListener, "captureListener");
        MatchSnapshot snapshot = match.snapshot();
        createServices(snapshot.matchId(), snapshot.lifecycleRevision(), snapshot.createdAt());
        matchSubscription = match.subscribe(this::onMatchEvent, false);
    }

    public synchronized void tick(ObjectivePresenceFrame frame) {
        if (closed) return;
        MatchSnapshot snapshot = match.snapshot();
        if (objectives == null) return;
        if (!objectives.snapshots().isEmpty()
            && !objectives.snapshots().getFirst().matchId().equals(snapshot.matchId())) {
            recreate(snapshot.matchId(), snapshot.lifecycleRevision(), snapshot.stateEnteredAt());
        }
        objectives.synchronizeLifecycle(snapshot.lifecycleRevision());
        if (frame != null) objectives.process(frame, snapshot.state());
    }

    public synchronized TicketOperationResult ticketOperation(TicketOperation operation) {
        MatchState state = match.snapshot().state();
        if (state != MatchState.WAITING
            && state != MatchState.WARMUP
            && state != MatchState.PLAYING) {
            return TicketOperationResult.rejected(
                "当前对局状态不允许修改票数", tickets.snapshot()
            );
        }
        return tickets.apply(operation);
    }

    public synchronized DefaultObjectiveService objectives() {
        return objectives;
    }

    public synchronized DefaultTicketService tickets() {
        return tickets;
    }

    private synchronized void onMatchEvent(MatchEvent event) {
        if (closed) return;
        if (event instanceof MatchCreatedEvent created) {
            MatchSnapshot snapshot = match.snapshot();
            recreate(created.matchId(), snapshot.lifecycleRevision(), created.occurredAt());
        } else if (event instanceof MatchResetStartedEvent) {
            closeCurrentServices();
        } else if (event instanceof MatchStateChangedEvent changed
            && objectives != null) {
            objectives.synchronizeLifecycle(changed.transition().lifecycleRevision());
        }
    }

    private void createServices(UUID matchId, long revision, Instant now) {
        objectives = new DefaultObjectiveService(
            matchId, revision, objectiveConfiguration, now, failureLogger
        );
        tickets = new DefaultTicketService(
            matchId, ticketConfiguration, now, failureLogger
        );
        objectiveSubscription = objectives.subscribe(this::onObjectiveEvent);
        sectorSubscription = objectives.subscribeSector(this::onObjectiveSectorEvent);
        ticketSubscription = tickets.subscribe(this::onTicketEvent);
    }

    private void recreate(UUID matchId, long revision, Instant now) {
        closeCurrentServices();
        createServices(matchId, revision, now);
    }

    private void onObjectiveEvent(ObjectiveEvent event) {
        if (!(event instanceof ObjectiveCapturedEvent captured)) return;
        if (captured.ticketReward() > 0) {
            UUID operationId = UUID.nameUUIDFromBytes((
                captured.matchId() + ":" + captured.objectiveId() + ":"
                    + captured.revision() + ":" + captured.capturedBy()
            ).getBytes(StandardCharsets.UTF_8));
            ticketOperation(new TicketOperation(
                operationId, captured.capturedBy(), TicketOperationType.ADD,
                captured.ticketReward(), TicketChangeReason.OBJECTIVE_CAPTURE_REWARD,
                captured.occurredAt()
            ));
        }
        try {
            captureListener.accept(captured);
        } catch (RuntimeException exception) {
            failureLogger.accept(exception);
        }
    }

    private void onObjectiveSectorEvent(ObjectiveSectorEvent event) {
        if (!(event instanceof ObjectiveSectorCompletedEvent completed)) return;
        MatchSnapshot snapshot = match.snapshot();
        if (!snapshot.matchId().equals(completed.matchId())
            || snapshot.state() != MatchState.PLAYING
            || objectives == null
            || !objectives.attackerVictoryOnFinalSector()
            || !objectives.isFinalSector(completed.sectorId())) {
            return;
        }
        match.end(MatchEndReason.OBJECTIVE_COMPLETED, "攻击方突破全部防线");
    }


    private void onTicketEvent(TicketEvent event) {
        if (!(event instanceof TicketsDepletedEvent depleted)) return;
        if (depleted.teamSide() != com.warsim.frontline.api.roster.TeamSide.ATTACKERS
            || !ticketConfiguration.endMatchOnAttackersDepleted()) {
            return;
        }
        MatchSnapshot snapshot = match.snapshot();
        if (snapshot.matchId().equals(depleted.matchId())
            && snapshot.state() == MatchState.PLAYING) {
            match.end(MatchEndReason.TICKETS_DEPLETED, "进攻方兵力票数耗尽");
        }
    }

    private void closeCurrentServices() {
        closeQuietly(objectiveSubscription);
        closeQuietly(sectorSubscription);
        closeQuietly(ticketSubscription);
        objectiveSubscription = null;
        sectorSubscription = null;
        ticketSubscription = null;
        if (objectives != null) objectives.close();
        if (tickets != null) tickets.close();
        objectives = null;
        tickets = null;
    }

    private void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception exception) {
            failureLogger.accept(new RuntimeException("Failed to close objective resource", exception));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        closeCurrentServices();
        closeQuietly(matchSubscription);
    }
}
