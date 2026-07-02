package com.warsim.frontline.match.objective;

import static org.junit.jupiter.api.Assertions.*;

import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.objective.*;
import com.warsim.frontline.api.roster.TeamSide;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultObjectiveServiceTest {
    private static final UUID MATCH = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");

    @Test void startsWithConfiguredOwner() {
        assertEquals(ObjectiveOwner.DEFENDERS, service().snapshot(id()).owner());
    }

    @Test void startsFullyControlled() {
        assertEquals(1.0, service().snapshot(id()).progress());
    }

    @Test void waitingDoesNotProgress() {
        DefaultObjectiveService service = service();
        service.process(frame(1, 1, 0), MatchState.WAITING);
        service.process(frame(1, 1, 1_000_000_000L), MatchState.WAITING);
        assertEquals(1.0, service.snapshot(id()).progress());
    }

    @Test void warmupDoesNotProgress() {
        DefaultObjectiveService service = service();
        service.process(frame(1, 1, 0), MatchState.WARMUP);
        service.process(frame(1, 1, 1_000_000_000L), MatchState.WARMUP);
        assertEquals(1.0, service.snapshot(id()).progress());
    }

    @Test void lockedObjectiveDoesNotProgress() {
        DefaultObjectiveService service = new DefaultObjectiveService(
            MATCH, 1, new ObjectiveConfiguration(true, 5, List.of(definition(true))), NOW,
            ignored -> {}
        );
        service.process(frame(1, 1, 0), MatchState.PLAYING);
        service.process(frame(1, 1, 1_000_000_000L), MatchState.PLAYING);
        assertEquals(1.0, service.snapshot(id()).progress());
    }

    @Test void attackersNeutralizeDefenderControl() {
        DefaultObjectiveService service = service();
        service.process(frame(1, 0, 0), MatchState.PLAYING);
        service.process(frame(1, 0, 1_000_000_000L), MatchState.PLAYING);
        assertTrue(service.snapshot(id()).progress() < 1.0);
        assertEquals(ObjectiveState.NEUTRALIZING, service.snapshot(id()).state());
    }

    @Test void equalPresenceContestsAndFreezes() {
        DefaultObjectiveService service = service();
        service.process(frame(1, 1, 0), MatchState.PLAYING);
        service.process(frame(1, 1, 1_000_000_000L), MatchState.PLAYING);
        assertEquals(ObjectiveState.CONTESTED, service.snapshot(id()).state());
        assertEquals(1.0, service.snapshot(id()).progress());
    }

    @Test void netPlayerDifferenceControlsSpeed() {
        DefaultObjectiveService one = service();
        one.process(frame(1, 0, 0), MatchState.PLAYING);
        one.process(frame(1, 0, 1_000_000_000L), MatchState.PLAYING);
        DefaultObjectiveService three = service();
        three.process(frame(3, 0, 0), MatchState.PLAYING);
        three.process(frame(3, 0, 1_000_000_000L), MatchState.PLAYING);
        assertTrue(three.snapshot(id()).progress() < one.snapshot(id()).progress());
    }

    @Test void deltaIsCappedAtOneSecond() {
        DefaultObjectiveService service = service();
        service.process(frame(1, 0, 0), MatchState.PLAYING);
        service.process(frame(1, 0, 60_000_000_000L), MatchState.PLAYING);
        assertEquals(29.0 / 30.0, service.snapshot(id()).progress(), 1.0e-6);
    }

    @Test void staleMatchFrameRejected() {
        DefaultObjectiveService service = service();
        ObjectivePresenceFrame stale = new ObjectivePresenceFrame(UUID.randomUUID(), 1, 0, NOW,
            List.of());
        assertFalse(service.process(stale, MatchState.PLAYING));
        assertEquals(1, service.metrics().staleFramesRejected());
    }

    @Test void staleRevisionFrameRejected() {
        DefaultObjectiveService service = service();
        assertFalse(service.process(frameWithRevision(2), MatchState.PLAYING));
    }

    @Test void neutralizationEventOnlyOnce() {
        DefaultObjectiveService service = fastService();
        AtomicInteger events = new AtomicInteger();
        service.subscribe(event -> {
            if (event instanceof ObjectiveNeutralizedEvent) events.incrementAndGet();
        });
        service.process(frame(1, 0, 0), MatchState.PLAYING);
        for (int second = 1; second <= 7; second++) {
            service.process(frame(1, 0, second * 1_000_000_000L), MatchState.PLAYING);
        }
        assertEquals(1, events.get());
    }

    @Test void captureEventAndRewardOnlyOnce() {
        DefaultObjectiveService service = fastService();
        AtomicInteger captures = new AtomicInteger();
        service.subscribe(event -> {
            if (event instanceof ObjectiveCapturedEvent) captures.incrementAndGet();
        });
        service.process(frame(1, 0, 0), MatchState.PLAYING);
        for (int second = 1; second <= 12; second++) {
            service.process(frame(1, 0, second * 1_000_000_000L), MatchState.PLAYING);
        }
        assertEquals(ObjectiveOwner.ATTACKERS, service.snapshot(id()).owner());
        assertEquals(1, captures.get());
    }

    @Test void resetRestoresInitialOwner() {
        DefaultObjectiveService service = fastService();
        service.unlock(id(), NOW);
        service.reset(id(), NOW);
        assertEquals(ObjectiveOwner.DEFENDERS, service.snapshot(id()).owner());
    }

    @Test void listenerFailureDoesNotBlockOthers() {
        AtomicInteger failures = new AtomicInteger();
        DefaultObjectiveService service = new DefaultObjectiveService(
            MATCH, 1, configuration(5), NOW, ignored -> failures.incrementAndGet()
        );
        AtomicInteger called = new AtomicInteger();
        service.subscribe(event -> { throw new RuntimeException("boom"); });
        service.subscribe(event -> called.incrementAndGet());
        service.unlock(id(), NOW);
        assertTrue(called.get() > 0);
        assertTrue(failures.get() > 0);
    }

    @Test void firstPlayingFrameDoesNotAdvance() {
        DefaultObjectiveService service = service();
        service.process(frame(1, 0, 9_000_000_000L), MatchState.PLAYING);
        assertEquals(1.0, service.snapshot(id()).progress());
    }

    @Test void maximumEffectivePlayersCapsSpeed() {
        DefaultObjectiveService four = service();
        four.process(frame(4, 0, 0), MatchState.PLAYING);
        four.process(frame(4, 0, 1_000_000_000L), MatchState.PLAYING);
        DefaultObjectiveService hundred = service();
        hundred.process(frame(100, 0, 0), MatchState.PLAYING);
        hundred.process(frame(100, 0, 1_000_000_000L), MatchState.PLAYING);
        assertEquals(four.snapshot(id()).progress(), hundred.snapshot(id()).progress());
    }

    @Test void ownerPresenceRestoresPartialControl() {
        DefaultObjectiveService service = service();
        service.process(frame(1, 0, 0), MatchState.PLAYING);
        service.process(frame(1, 0, 1_000_000_000L), MatchState.PLAYING);
        double damaged = service.snapshot(id()).progress();
        service.process(frame(0, 1, 2_000_000_000L), MatchState.PLAYING);
        assertTrue(service.snapshot(id()).progress() > damaged);
    }

    @Test void emptyAreaRestoresOwnerControl() {
        DefaultObjectiveService service = service();
        service.process(frame(1, 0, 0), MatchState.PLAYING);
        service.process(frame(1, 0, 1_000_000_000L), MatchState.PLAYING);
        double damaged = service.snapshot(id()).progress();
        service.process(frame(0, 0, 2_000_000_000L), MatchState.PLAYING);
        assertTrue(service.snapshot(id()).progress() > damaged);
    }

    @Test void endingFreezesProgress() {
        DefaultObjectiveService service = service();
        service.process(frame(1, 0, 0), MatchState.PLAYING);
        service.process(frame(1, 0, 1_000_000_000L), MatchState.PLAYING);
        double progress = service.snapshot(id()).progress();
        service.process(frame(1, 0, 2_000_000_000L), MatchState.ENDING);
        assertEquals(progress, service.snapshot(id()).progress());
    }

    @Test void lockCanBeReversed() {
        DefaultObjectiveService service = service();
        service.lock(id(), NOW);
        assertTrue(service.snapshot(id()).locked());
        service.unlock(id(), NOW.plusSeconds(1));
        assertFalse(service.snapshot(id()).locked());
    }

    @Test void setNeutralSetsZeroProgress() {
        DefaultObjectiveService service = service();
        service.setOwner(id(), ObjectiveOwner.NEUTRAL, NOW);
        assertEquals(0, service.snapshot(id()).progress());
        assertEquals(ObjectiveState.IDLE, service.snapshot(id()).state());
    }

    @Test void setOwnerDoesNotPublishCapturedEvent() {
        DefaultObjectiveService service = service();
        AtomicInteger captures = new AtomicInteger();
        service.subscribe(event -> {
            if (event instanceof ObjectiveCapturedEvent) captures.incrementAndGet();
        });
        service.setOwner(id(), ObjectiveOwner.ATTACKERS, NOW);
        assertEquals(0, captures.get());
    }

    @Test void decreasingMonotonicTimeRejected() {
        DefaultObjectiveService service = service();
        service.process(frame(1, 0, 10), MatchState.PLAYING);
        assertFalse(service.process(frame(1, 0, 9), MatchState.PLAYING));
        assertEquals(1, service.metrics().invalidPresenceFrames());
    }

    @Test void closeIsIdempotentAndClearsObjectives() {
        DefaultObjectiveService service = service();
        service.close();
        assertDoesNotThrow(service::close);
        assertEquals(ObjectiveSystemState.CLOSED, service.systemState());
        assertTrue(service.snapshots().isEmpty());
    }

    private static DefaultObjectiveService service() {
        return new DefaultObjectiveService(MATCH, 1, configuration(30), NOW, ignored -> {});
    }

    private static DefaultObjectiveService fastService() {
        return new DefaultObjectiveService(MATCH, 1, configuration(5), NOW, ignored -> {});
    }

    private static ObjectiveConfiguration configuration(int seconds) {
        return new ObjectiveConfiguration(true, 5, List.of(definition(false, seconds)));
    }

    private static ObjectiveDefinition definition(boolean locked) {
        return definition(locked, 30);
    }

    private static ObjectiveDefinition definition(boolean locked, int seconds) {
        return new ObjectiveDefinition(id(), "A点",
            new ObjectiveRegion("world", 0, 64, 0, 8, 6),
            ObjectiveOwner.DEFENDERS, locked,
            new ObjectiveCaptureRules(seconds, 4, .5, EmptyBehavior.RETURN_TO_OWNER,
                ContestedBehavior.FREEZE),
            new ObjectiveRewards(50, 0));
    }

    private static ObjectiveId id() {
        return new ObjectiveId("alpha");
    }

    private static ObjectivePresenceFrame frame(int attackers, int defenders, long nanos) {
        java.util.ArrayList<ObjectivePlayerPresence> players = new java.util.ArrayList<>();
        for (int i = 0; i < attackers; i++) {
            players.add(new ObjectivePlayerPresence(UUID.randomUUID(), TeamSide.ATTACKERS,
                "world", 0, 64, 0));
        }
        for (int i = 0; i < defenders; i++) {
            players.add(new ObjectivePlayerPresence(UUID.randomUUID(), TeamSide.DEFENDERS,
                "world", 0, 64, 0));
        }
        return new ObjectivePresenceFrame(MATCH, 1, nanos, NOW.plusNanos(nanos), players);
    }

    private static ObjectivePresenceFrame frameWithRevision(long revision) {
        return new ObjectivePresenceFrame(MATCH, revision, 0, NOW, List.of());
    }
}
