package com.warsim.frontline.match;

import static org.junit.jupiter.api.Assertions.*;

import com.warsim.frontline.api.match.*;
import com.warsim.frontline.api.roster.RosterConfiguration;
import com.warsim.frontline.squad.DefaultRosterService;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MatchRosterCoordinatorTest {
    private static final Instant NOW=Instant.parse("2026-06-21T00:00:00Z");

    @Test void successfulAdmissionCreatesParticipantAndRosterAssignment() {
        var fixture=fixture(100);
        var result=fixture.coordinator.admit(id(1),"Player1",NOW);
        assertTrue(result.accepted());
        assertTrue(fixture.roster.assignment(id(1)).isPresent());
        assertEquals(1,fixture.match.snapshot().currentPlayers());
    }

    @Test void rosterExistsBeforeParticipantJoinedEventIsPublished() throws Exception {
        var fixture=fixture(100);
        AtomicBoolean visible=new AtomicBoolean();
        fixture.match.subscribe(event->{
            if(event instanceof MatchParticipantJoinedEvent joined) {
                visible.set(fixture.roster.assignment(joined.playerUuid()).isPresent());
            }
        },false);
        fixture.coordinator.admit(id(1),"Player1",NOW);
        assertTrue(visible.get());
    }

    @Test void participantFailureRollsBackRosterAssignment() {
        var fixture=fixture(2);
        assertTrue(fixture.coordinator.admit(id(1),"Player1",NOW).accepted());
        assertTrue(fixture.coordinator.admit(id(2),"Player2",NOW).accepted());
        assertFalse(fixture.coordinator.admit(id(3),"Player3",NOW).accepted());
        assertTrue(fixture.roster.assignment(id(3)).isEmpty());
        assertEquals(2,fixture.roster.snapshot().activeAssignments());
    }

    @Test void disconnectCreatesReservationThenRemovesParticipant() {
        var fixture=fixture(100);
        fixture.coordinator.admit(id(1),"Player1",NOW);
        fixture.coordinator.disconnect(id(1),NOW.plusSeconds(1));
        assertEquals(1,fixture.roster.snapshot().disconnectedReservations());
        assertEquals(0,fixture.match.snapshot().currentPlayers());
    }

    @Test void newMatchCreatesFreshRoster() {
        var fixture=fixture(100); fixture.coordinator.admit(id(1),"Player1",NOW);
        fixture.match.reset(); fixture.match.tick(1,NOW.plusSeconds(1));
        fixture.coordinator.tick(NOW.plusSeconds(1));
        assertEquals(fixture.match.snapshot().matchId(),fixture.roster.snapshot().matchId());
        assertEquals(0,fixture.roster.snapshot().activeAssignments());
    }

    @Test void closeClearsRosterAndParticipants() {
        var fixture=fixture(100); fixture.coordinator.admit(id(1),"Player1",NOW);
        fixture.coordinator.close();
        assertEquals(0,fixture.roster.snapshot().activeAssignments());
        assertEquals(MatchState.STOPPED,fixture.match.snapshot().state());
    }

    private static Fixture fixture(int matchCapacity) {
        var config=new MatchConfiguration(true,MatchConfiguration.OFFENSIVE_MODE,1,matchCapacity,
            true,true,5,60,3,5,false,true,false,java.util.List.of(5));
        var match=new DefaultMatchService("official-war-01",config,MatchResetService.noOp(),
            Runnable::run,NOW,0);
        var roster=new DefaultRosterService(match.snapshot().matchId(),RosterConfiguration.defaults(true),NOW);
        return new Fixture(match,roster,new MatchRosterCoordinator(match,roster));
    }

    private static UUID id(int value){return new UUID(0,value);}
    private record Fixture(DefaultMatchService match,DefaultRosterService roster,MatchRosterCoordinator coordinator){}
}
