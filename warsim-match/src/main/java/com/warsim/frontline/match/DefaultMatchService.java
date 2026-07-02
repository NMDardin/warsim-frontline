package com.warsim.frontline.match;

import com.warsim.frontline.api.match.*;
import com.warsim.frontline.api.node.NodeIds;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.warsim.frontline.api.roster.RosterOperationResult;

public final class DefaultMatchService implements MatchService {
    private static final int HISTORY_LIMIT = 10;

    private final String nodeId;
    private final MatchConfiguration configuration;
    private final MatchResetService resetService;
    private final Executor callbackExecutor;
    private final MatchParticipantRegistry participants = new MatchParticipantRegistry();
    private final MutableMatchMetrics metrics = new MutableMatchMetrics();
    private final MatchEventDispatcher events;
    private final ArrayDeque<MatchSummary> history = new ArrayDeque<>();
    private final AtomicReference<PendingReset> pendingReset = new AtomicReference<>();

    private UUID matchId;
    private MatchState state;
    private long revision;
    private Instant createdAt;
    private Instant stateEnteredAt;
    private Instant scheduledStartAt;
    private Instant roundStartedAt;
    private Instant scheduledEndAt;
    private MatchEndReason endReason;
    private String endSummary;
    private String lastError;
    private boolean manualWaiting;
    private boolean forcedWarmup;
    private long stateDeadlineNanos = Long.MAX_VALUE;
    private long lastMonotonicNanos;
    private Instant lastWallTime;
    private boolean closed;

    public DefaultMatchService(
        String nodeId,
        MatchConfiguration configuration,
        MatchResetService resetService,
        Executor callbackExecutor,
        Instant initialWallTime,
        long initialMonotonicNanos
    ) {
        this(
            nodeId, configuration, resetService, callbackExecutor, initialWallTime,
            initialMonotonicNanos, exception -> { }
        );
    }

    public DefaultMatchService(
        String nodeId,
        MatchConfiguration configuration,
        MatchResetService resetService,
        Executor callbackExecutor,
        Instant initialWallTime,
        long initialMonotonicNanos,
        Consumer<RuntimeException> listenerFailureLogger
    ) {
        this.nodeId = NodeIds.requireValid(nodeId);
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.events = new MatchEventDispatcher(exception -> {
            metrics.listenerFailures.incrementAndGet();
            listenerFailureLogger.accept(exception);
        });
        this.lastWallTime = Objects.requireNonNull(initialWallTime, "initialWallTime");
        this.lastMonotonicNanos = initialMonotonicNanos;
        createMatch(false, initialWallTime);
    }

    @Override
    public synchronized MatchSnapshot snapshot() {
        return new MatchSnapshot(
            matchId, nodeId, configuration.modeId(), state, revision, createdAt, stateEnteredAt,
            scheduledStartAt, roundStartedAt, scheduledEndAt, endReason, endSummary, lastError,
            participants.size(), configuration.maximumPlayers(), configuration.minimumPlayers(),
            acceptingPlayers(), manualWaiting
        );
    }

    @Override
    public synchronized MatchMetricsSnapshot metrics() {
        return metrics.snapshot(state, revision, participants.size());
    }

    @Override
    public synchronized List<MatchSummary> history() {
        return List.copyOf(history);
    }

    @Override
    public synchronized MatchOperationResult start(boolean force) {
        if (state != MatchState.WAITING) {
            return reject("当前状态不允许启动热身");
        }
        if (!force && participants.size() < configuration.minimumPlayers()) {
            return reject("当前人数未达到最低开局人数");
        }
        metrics.administratorStarts.incrementAndGet();
        forcedWarmup = force;
        transition(MatchState.WARMUP, force ? "administrator-force-start" : "administrator-start");
        return MatchOperationResult.success("热身已开始");
    }

    @Override
    public synchronized MatchOperationResult end(MatchEndReason reason, String summary) {
        Objects.requireNonNull(reason, "reason");
        String safeSummary = sanitizeSummary(summary);
        if (state == MatchState.WARMUP) {
            forcedWarmup = false;
            scheduledStartAt = null;
            transition(MatchState.WAITING, "administrator-cancel-warmup");
            return MatchOperationResult.success("热身已取消");
        }
        if (state != MatchState.PLAYING) {
            return reject("当前状态不允许结束对局");
        }
        metrics.administratorEnds.incrementAndGet();
        enterEnding(reason, safeSummary, "administrator-end");
        return MatchOperationResult.success("对局正在结束");
    }

    @Override
    public synchronized MatchOperationResult reset() {
        if (state == MatchState.FAILED) {
            return recoverInternal("administrator-reset-failed");
        }
        if (state != MatchState.WAITING && state != MatchState.ENDING) {
            return reject("当前状态不允许重置；进行中的对局请先结束");
        }
        endReason = MatchEndReason.RESET_REQUESTED;
        transition(MatchState.RESETTING, "administrator-reset");
        beginReset();
        return MatchOperationResult.success("战场正在重置");
    }

    @Override
    public synchronized MatchOperationResult recover() {
        if (state != MatchState.FAILED) {
            return reject("仅FAILED状态允许恢复");
        }
        return recoverInternal("administrator-recover");
    }

    @Override
    public synchronized MatchParticipantJoinResult participantJoined(
        UUID playerUuid,
        String name,
        Instant joinedAt
    ) {
        return participantJoinedAtomic(
            playerUuid, name, joinedAt,
            () -> RosterOperationResult.success("无需Roster提交", null)
        );
    }

    @Override
    public synchronized java.util.Optional<MatchParticipant> participant(UUID playerUuid) {
        return participants.find(playerUuid);
    }

    synchronized MatchParticipantJoinResult participantJoinedAtomic(
        UUID playerUuid,
        String name,
        Instant joinedAt,
        Supplier<RosterOperationResult> rosterCommit
    ) {
        if (!acceptingPlayers()) {
            return MatchParticipantJoinResult.rejected("当前战场暂不可加入");
        }
        boolean duringRound = state == MatchState.PLAYING;
        MatchParticipantState participantState = duringRound
            ? MatchParticipantState.ACTIVE : MatchParticipantState.WAITING;
        MatchParticipantJoinResult result = participants.join(
            playerUuid, name, matchId, joinedAt, participantState, duringRound,
            configuration.maximumPlayers()
        );
        if (!result.accepted()) return result;
        RosterOperationResult commit = rosterCommit.get();
        if (!commit.successful()) {
            participants.leave(playerUuid);
            return MatchParticipantJoinResult.rejected(commit.message());
        }
        if (result.created()) {
            metrics.peakParticipants.accumulateAndGet(participants.peak(), Math::max);
            events.publish(new MatchParticipantJoinedEvent(matchId, joinedAt, playerUuid));
        }
        return result;
    }

    @Override
    public synchronized boolean participantLeft(UUID playerUuid, Instant leftAt) {
        boolean removed = participants.leave(playerUuid);
        if (removed) {
            events.publish(new MatchParticipantLeftEvent(matchId, leftAt, playerUuid));
        }
        return removed;
    }

    @Override
    public synchronized AutoCloseable subscribe(MatchEventListener listener, boolean matchScoped) {
        return events.subscribe(listener, matchScoped);
    }

    @Override
    public synchronized void tick(long monotonicNanos, Instant wallTime) {
        if (closed || state == MatchState.STOPPED) {
            return;
        }
        lastMonotonicNanos = Math.max(lastMonotonicNanos, monotonicNanos);
        lastWallTime = Objects.requireNonNull(wallTime, "wallTime");
        if (state == MatchState.RESETTING) {
            PendingReset completion = pendingReset.getAndSet(null);
            if (completion != null) {
                applyResetCompletion(completion);
                return;
            }
            if (lastMonotonicNanos >= stateDeadlineNanos) {
                metrics.resetFailures.incrementAndGet();
                fail("战场重置超时");
            }
            return;
        }
        switch (state) {
            case WAITING -> {
                if (configuration.autoStart() && !manualWaiting
                    && participants.size() >= configuration.minimumPlayers()) {
                    forcedWarmup = false;
                    transition(MatchState.WARMUP, "minimum-players-reached");
                }
            }
            case WARMUP -> {
                if (!forcedWarmup && configuration.cancelWarmupBelowMinimum()
                    && participants.size() < configuration.minimumPlayers()) {
                    metrics.warmupCancellations.incrementAndGet();
                    scheduledStartAt = null;
                    transition(MatchState.WAITING, "players-below-minimum");
                } else if (lastMonotonicNanos >= stateDeadlineNanos) {
                    transition(MatchState.PLAYING, "warmup-completed");
                }
            }
            case PLAYING -> {
                if (lastMonotonicNanos >= stateDeadlineNanos) {
                    enterEnding(MatchEndReason.TIME_LIMIT, "回合时间结束", "round-time-limit");
                }
            }
            case ENDING -> {
                if (lastMonotonicNanos >= stateDeadlineNanos) {
                    transition(MatchState.RESETTING, "ending-completed");
                    beginReset();
                }
            }
            default -> {
            }
        }
    }

    public synchronized boolean rejectStaleTask(UUID taskMatchId, long taskRevision) {
        boolean stale = !matchId.equals(taskMatchId) || revision != taskRevision;
        if (stale) {
            metrics.staleTasks.incrementAndGet();
        }
        return stale;
    }

    public synchronized void failInitialization(String summary) {
        if (state == MatchState.STOPPED || state == MatchState.STOPPING) {
            return;
        }
        fail(summary);
    }

    @Override
    public synchronized void stop() {
        if (state == MatchState.STOPPED) {
            return;
        }
        if (state != MatchState.STOPPING) {
            endReason = MatchEndReason.SERVER_SHUTDOWN;
            transition(MatchState.STOPPING, "server-shutdown");
        }
        participants.clear();
        events.clearMatchScoped();
        transition(MatchState.STOPPED, "shutdown-completed");
        closed = true;
    }

    @Override
    public void close() {
        stop();
        events.clear();
    }

    private void createMatch(boolean manual, Instant now) {
        matchId = UUID.randomUUID();
        state = MatchState.BOOTSTRAPPING;
        revision = 0;
        createdAt = now;
        stateEnteredAt = now;
        scheduledStartAt = null;
        roundStartedAt = null;
        scheduledEndAt = null;
        endReason = null;
        endSummary = null;
        lastError = null;
        manualWaiting = manual;
        forcedWarmup = false;
        stateDeadlineNanos = Long.MAX_VALUE;
        metrics.created.incrementAndGet();
        events.publish(new MatchCreatedEvent(matchId, now));
        transition(MatchState.WAITING, "bootstrap-completed");
    }

    private void transition(MatchState target, String reason) {
        if (!state.canTransitionTo(target)) {
            metrics.invalidTransitions.incrementAndGet();
            throw new IllegalStateException("Illegal match transition " + state + " -> " + target);
        }
        MatchState previous = state;
        state = target;
        revision++;
        stateEnteredAt = lastWallTime;
        configureStateTiming(target);
        MatchTransition transition = new MatchTransition(
            matchId, previous, target, reason, lastWallTime, revision
        );
        metrics.transitions.incrementAndGet();
        metrics.lastTransition.set(lastWallTime);
        events.publish(new MatchStateChangedEvent(matchId, lastWallTime, transition));
        if (target == MatchState.PLAYING) {
            participants.activateAll(matchId);
            metrics.lastStarted.set(lastWallTime);
            events.publish(new MatchStartedEvent(matchId, lastWallTime));
        } else if (target == MatchState.ENDING) {
            metrics.lastEnded.set(lastWallTime);
            events.publish(new MatchEndedEvent(matchId, lastWallTime, endReason));
        } else if (target == MatchState.RESETTING) {
            events.publish(new MatchResetStartedEvent(matchId, lastWallTime));
        }
    }

    private void configureStateTiming(MatchState target) {
        switch (target) {
            case WARMUP -> {
                stateDeadlineNanos = lastMonotonicNanos + seconds(configuration.warmupSeconds());
                scheduledStartAt = lastWallTime.plusSeconds(configuration.warmupSeconds());
            }
            case PLAYING -> {
                forcedWarmup = false;
                roundStartedAt = lastWallTime;
                scheduledEndAt = lastWallTime.plusSeconds(configuration.roundDurationSeconds());
                stateDeadlineNanos =
                    lastMonotonicNanos + seconds(configuration.roundDurationSeconds());
            }
            case ENDING -> stateDeadlineNanos =
                lastMonotonicNanos + seconds(configuration.endingSeconds());
            case RESETTING -> stateDeadlineNanos =
                lastMonotonicNanos + seconds(configuration.resetTimeoutSeconds());
            default -> stateDeadlineNanos = Long.MAX_VALUE;
        }
    }

    private void enterEnding(MatchEndReason reason, String summary, String transitionReason) {
        endReason = reason;
        endSummary = summary;
        transition(MatchState.ENDING, transitionReason);
    }

    private void beginReset() {
        metrics.resets.incrementAndGet();
        UUID expectedMatchId = matchId;
        long expectedRevision = revision;
        MatchResetContext context =
            new MatchResetContext(expectedMatchId, expectedRevision, nodeId, lastWallTime);
        CompletableFuture<MatchResetResult> future;
        try {
            future = resetService.reset(context);
        } catch (RuntimeException exception) {
            future = CompletableFuture.failedFuture(exception);
        }
        future.whenComplete((result, failure) -> callbackExecutor.execute(() ->
            acceptResetCompletion(expectedMatchId, expectedRevision, result, failure)
        ));
    }

    private synchronized void acceptResetCompletion(
        UUID expectedMatchId,
        long expectedRevision,
        MatchResetResult result,
        Throwable failure
    ) {
        if (!matchId.equals(expectedMatchId)
            || revision != expectedRevision
            || state != MatchState.RESETTING) {
            metrics.staleTasks.incrementAndGet();
            return;
        }
        pendingReset.set(new PendingReset(expectedMatchId, expectedRevision, result, failure));
    }

    private void applyResetCompletion(PendingReset completion) {
        if (!matchId.equals(completion.matchId())
            || revision != completion.revision()
            || state != MatchState.RESETTING) {
            metrics.staleTasks.incrementAndGet();
            return;
        }
        if (completion.failure() != null
            || completion.result() == null
            || !completion.result().successful()) {
            metrics.resetFailures.incrementAndGet();
            String summary = completion.failure() == null
                ? completion.result().summary() : "战场重置发生内部异常";
            fail(summary);
            return;
        }
        events.publish(new MatchResetCompletedEvent(matchId, lastWallTime));
        archive(false);
        participants.clear();
        events.clearMatchScoped();
        metrics.completed.incrementAndGet();
        createMatch(!configuration.autoCycle(), lastWallTime);
    }

    private MatchOperationResult recoverInternal(String reason) {
        archive(true);
        participants.clear();
        events.clearMatchScoped();
        pendingReset.set(null);
        createMatch(!configuration.autoCycle(), lastWallTime);
        return MatchOperationResult.success("战场已恢复并创建新对局");
    }

    private void fail(String summary) {
        lastError = sanitizeSummary(summary);
        endReason = MatchEndReason.INTERNAL_ERROR;
        participants.clear();
        if (state != MatchState.FAILED) {
            transition(MatchState.FAILED, "internal-error");
            metrics.failed.incrementAndGet();
        }
    }

    private void archive(boolean failed) {
        history.addLast(new MatchSummary(
            matchId, createdAt, lastWallTime, endReason, participants.peak(), failed
        ));
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    private MatchOperationResult reject(String message) {
        metrics.invalidTransitions.incrementAndGet();
        return MatchOperationResult.rejected(message);
    }

    private boolean acceptingPlayers() {
        return switch (state) {
            case WAITING, WARMUP -> participants.size() < configuration.maximumPlayers();
            case PLAYING -> configuration.allowMidRoundJoin()
                && participants.size() < configuration.maximumPlayers();
            default -> false;
        };
    }

    private static String sanitizeSummary(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\p{Cc}", "").trim();
        return cleaned.length() <= 128 ? cleaned : cleaned.substring(0, 128);
    }

    private static long seconds(long seconds) {
        return seconds * 1_000_000_000L;
    }

    private record PendingReset(
        UUID matchId,
        long revision,
        MatchResetResult result,
        Throwable failure
    ) {
    }
}
