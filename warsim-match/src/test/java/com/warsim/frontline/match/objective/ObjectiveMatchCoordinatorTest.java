package com.warsim.frontline.match.objective;

import static org.junit.jupiter.api.Assertions.*;

import com.warsim.frontline.api.match.*;
import com.warsim.frontline.api.objective.*;
import com.warsim.frontline.api.ticket.*;
import com.warsim.frontline.match.DefaultMatchService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObjectiveMatchCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");

    @Test void ticketDepletionEndsPlayingMatchOnce() {
        Fixture fixture = fixture();
        fixture.match.start(true);
        fixture.match.tick(5_000_000_000L, NOW.plusSeconds(5));
        fixture.coordinator.ticketOperation(new TicketOperation(
            UUID.randomUUID(), com.warsim.frontline.api.roster.TeamSide.ATTACKERS,
            TicketOperationType.SET, 0, TicketChangeReason.ADMINISTRATOR, NOW.plusSeconds(6)
        ));
        assertEquals(MatchState.ENDING, fixture.match.snapshot().state());
        assertEquals(MatchEndReason.TICKETS_DEPLETED, fixture.match.snapshot().endReason());
        assertEquals(1, fixture.coordinator.tickets().metrics().depletedEvents());
    }

    @Test void newMatchCreatesFreshObjectiveAndTickets() {
        Fixture fixture = fixture();
        UUID old = fixture.match.snapshot().matchId();
        fixture.coordinator.objectives().setOwner(new ObjectiveId("alpha"),
            ObjectiveOwner.ATTACKERS, NOW);
        fixture.coordinator.ticketOperation(operation(TicketOperationType.TAKE, 20));
        fixture.match.reset();
        fixture.match.tick(1, NOW.plusSeconds(1));
        fixture.coordinator.tick(null);
        assertNotEquals(old, fixture.match.snapshot().matchId());
        assertEquals(ObjectiveOwner.DEFENDERS,
            fixture.coordinator.objectives().snapshot(new ObjectiveId("alpha")).owner());
        assertEquals(300, fixture.coordinator.tickets().snapshot().attackers().current());
    }

    @Test void rewardIsAppliedFromCaptureEvent() {
        Fixture fixture = fixture();
        fixture.match.start(true);
        fixture.match.tick(5_000_000_000L, NOW.plusSeconds(5));
        DefaultObjectiveService objectives = fixture.coordinator.objectives();
        long revision = fixture.match.snapshot().lifecycleRevision();
        objectives.synchronizeLifecycle(revision);
        objectives.process(frame(fixture.match.snapshot().matchId(), revision, 0), MatchState.PLAYING);
        for (int i = 1; i <= 12; i++) {
            objectives.process(frame(fixture.match.snapshot().matchId(), revision,
                i * 1_000_000_000L), MatchState.PLAYING);
        }
        assertEquals(350, fixture.coordinator.tickets().snapshot().attackers().current());
        assertEquals(50, fixture.coordinator.tickets().metrics().objectiveRewards());
    }

    @Test void administratorSetOwnerDoesNotReward() {
        Fixture fixture = fixture();
        fixture.coordinator.objectives().setOwner(new ObjectiveId("alpha"),
            ObjectiveOwner.ATTACKERS, NOW);
        assertEquals(300, fixture.coordinator.tickets().snapshot().attackers().current());
    }

    @Test void endingRejectsAdministratorTicketChange() {
        Fixture fixture = fixture();
        fixture.match.start(true);
        fixture.match.tick(5_000_000_000L, NOW.plusSeconds(5));
        fixture.match.end(MatchEndReason.ADMIN_STOP, "");
        assertFalse(fixture.coordinator.ticketOperation(operation(
            TicketOperationType.ADD, 10)).successful());
    }

    @Test void captureNotificationFailureDoesNotBlockReward() {
        MatchConfiguration matchConfiguration = new MatchConfiguration(
            true, MatchConfiguration.OFFENSIVE_MODE, 1, 100, false, true,
            5, 60, 3, 5, false, true, false, List.of()
        );
        DefaultMatchService match = new DefaultMatchService(
            "official-war-01", matchConfiguration, MatchResetService.noOp(), Runnable::run,
            NOW, 0
        );
        ObjectiveConfiguration objectives = objectiveConfiguration();
        ObjectiveMatchCoordinator coordinator = new ObjectiveMatchCoordinator(
            match, objectives, TicketConfiguration.defaults(true), ignored -> {},
            ignored -> { throw new RuntimeException("display failure"); }
        );
        match.start(true);
        match.tick(5_000_000_000L, NOW.plusSeconds(5));
        long revision = match.snapshot().lifecycleRevision();
        coordinator.objectives().synchronizeLifecycle(revision);
        coordinator.objectives().process(frame(match.snapshot().matchId(), revision, 0),
            MatchState.PLAYING);
        for (int i = 1; i <= 12; i++) {
            coordinator.objectives().process(
                frame(match.snapshot().matchId(), revision, i * 1_000_000_000L),
                MatchState.PLAYING
            );
        }
        assertEquals(350, coordinator.tickets().snapshot().attackers().current());
    }

    private static Fixture fixture() {
        MatchConfiguration matchConfiguration = new MatchConfiguration(
            true, MatchConfiguration.OFFENSIVE_MODE, 1, 100, false, true,
            5, 60, 3, 5, false, true, false, List.of()
        );
        DefaultMatchService match = new DefaultMatchService(
            "official-war-01", matchConfiguration, MatchResetService.noOp(), Runnable::run,
            NOW, 0
        );
        ObjectiveConfiguration objectives = objectiveConfiguration();
        return new Fixture(match, new ObjectiveMatchCoordinator(
            match, objectives, TicketConfiguration.defaults(true), ignored -> {}
        ));
    }

    private static ObjectiveConfiguration objectiveConfiguration() {
        return new ObjectiveConfiguration(true, 5, List.of(
            new ObjectiveDefinition(new ObjectiveId("alpha"), "A点",
                new ObjectiveRegion("world", 0, 64, 0, 8, 6),
                ObjectiveOwner.DEFENDERS, false,
                new ObjectiveCaptureRules(5, 4, .5, EmptyBehavior.RETURN_TO_OWNER,
                    ContestedBehavior.FREEZE),
                new ObjectiveRewards(50, 0))
        ));
    }

    private static TicketOperation operation(TicketOperationType type, int amount) {
        return new TicketOperation(UUID.randomUUID(),
            com.warsim.frontline.api.roster.TeamSide.ATTACKERS, type, amount,
            TicketChangeReason.ADMINISTRATOR, NOW);
    }

    private static ObjectivePresenceFrame frame(UUID matchId, long revision, long nanos) {
        return new ObjectivePresenceFrame(matchId, revision, nanos, NOW.plusNanos(nanos),
            List.of(new ObjectivePlayerPresence(UUID.randomUUID(),
                com.warsim.frontline.api.roster.TeamSide.ATTACKERS, "world", 0, 64, 0)));
    }

    private record Fixture(DefaultMatchService match, ObjectiveMatchCoordinator coordinator) {}
}
