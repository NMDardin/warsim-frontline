package com.warsim.frontline.squad;

import static org.junit.jupiter.api.Assertions.*;

import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.roster.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultRosterServiceTest {
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");

    @Test void initializesTwoTeams() { assertEquals(2, service().snapshot().teams().size()); }
    @Test void initializesTenSquadsPerTeam() { assertEquals(20, service().snapshot().squads().size()); }
    @Test void emptyRosterHasNoAssignments() { assertEquals(0, service().snapshot().activeAssignments()); }
    @Test void firstAssignmentUsesAttackers() { assertEquals(TeamSide.ATTACKERS, admit(service(), 1).assignment().teamSide()); }
    @Test void tiedAssignmentsAlternate() {
        var s=service(); var sides=new ArrayList<TeamSide>();
        for(int i=0;i<8;i++) sides.add(admit(s,i).assignment().teamSide());
        assertEquals(List.of(TeamSide.ATTACKERS,TeamSide.DEFENDERS,TeamSide.ATTACKERS,TeamSide.DEFENDERS,
            TeamSide.ATTACKERS,TeamSide.DEFENDERS,TeamSide.ATTACKERS,TeamSide.DEFENDERS),sides);
    }
    @Test void smallerTeamIsPreferred() {
        var s=service(); var a=id(1); assertTrue(s.admit(a,"P1",NOW,MatchState.WAITING).successful());
        assertEquals(TeamSide.DEFENDERS,s.admit(id(2),"P2",NOW,MatchState.WAITING).assignment().teamSide());
    }
    @Test void hundredPlayersBecomeFiftyFifty() {
        var s=service(); for(int i=0;i<100;i++) assertTrue(admit(s,i).successful());
        assertEquals(50,s.snapshot().team(TeamSide.ATTACKERS).activeMembers());
        assertEquals(50,s.snapshot().team(TeamSide.DEFENDERS).activeMembers());
    }
    @Test void oneHundredAndFirstIsRejected() {
        var s=service(); for(int i=0;i<100;i++) admit(s,i);
        assertEquals(RosterFailure.TEAMS_FULL,s.admit(id(101),"P101",NOW,MatchState.WAITING).failure());
    }
    @Test void duplicateAdmissionIsIdempotent() {
        var s=service(); UUID p=id(1); var first=s.admit(p,"P1",NOW,MatchState.WAITING);
        var second=s.admit(p,"P1",NOW.plusSeconds(1),MatchState.WAITING);
        assertTrue(second.successful()); assertEquals(first.assignment(),second.assignment());
        assertEquals(1,s.snapshot().activeAssignments());
    }
    @Test void staleAdmissionPlanIsRejectedWithoutMutation() {
        var s=service(); var plan=s.prepareAdmission(id(1),"P1",NOW,MatchState.WAITING).plan();
        admit(s,2);
        var result=s.commitAdmission(plan);
        assertEquals(RosterFailure.STALE_PLAN,result.failure());
        assertTrue(s.assignment(id(1)).isEmpty());
    }
    @Test void firstPlayerJoinsAlpha() { assertEquals(SquadId.ALPHA,admit(service(),1).assignment().squadId().orElseThrow()); }
    @Test void secondPlayerOnOtherTeamAlsoJoinsAlpha() {
        var s=service(); admit(s,1);
        assertEquals(SquadId.ALPHA,admit(s,2).assignment().squadId().orElseThrow());
    }
    @Test void existingNonFullSquadIsPreferred() {
        var s=service(); var first=admit(s,1); admit(s,2); var third=admit(s,3);
        assertEquals(first.assignment().squadId(),third.assignment().squadId());
    }
    @Test void fullSquadMovesToNextSquad() {
        var s=service(); for(int i=0;i<10;i++) admit(s,i);
        assertEquals(SquadId.BRAVO,admit(s,10).assignment().squadId().orElseThrow());
    }
    @Test void hundredPlayersFillTwentySquads() {
        var s=service(); for(int i=0;i<100;i++) admit(s,i);
        assertEquals(20,s.snapshot().squads().stream().filter(q->q.currentMembers()==5).count());
    }
    @Test void teamAssignmentCanExistWithoutSquads() {
        var s=service(configuration(false)); var result=admit(s,1);
        assertTrue(result.assignment().squadId().isEmpty());
    }
    @Test void firstSquadMemberIsLeader() {
        var result=admit(service(),1);
        assertEquals(SquadRole.LEADER,result.assignment().squadRole());
    }
    @Test void secondSquadMemberIsMember() {
        var s=service(); admit(s,1); admit(s,2);
        assertEquals(SquadRole.MEMBER,admit(s,3).assignment().squadRole());
    }
    @Test void leaderDisconnectTransfersLeadership() {
        var s=service(); UUID leader=id(1); admit(s,1); admit(s,2); UUID successor=id(3); admit(s,3);
        s.disconnect(leader,NOW.plusSeconds(1));
        assertEquals(successor,s.snapshot().squad(TeamSide.ATTACKERS,SquadId.ALPHA).leaderUuid());
    }
    @Test void lastMemberDisconnectLeavesNoLeader() {
        var s=service(); UUID p=id(1); admit(s,1); s.disconnect(p,NOW.plusSeconds(1));
        assertNull(s.snapshot().squad(TeamSide.ATTACKERS,SquadId.ALPHA).leaderUuid());
    }
    @Test void explicitLeaderTransferSucceeds() {
        var s=service(); UUID a=id(1), b=id(3); admit(s,1); admit(s,2); admit(s,3);
        assertTrue(s.transferLeader(a,b,false,NOW).successful());
        assertEquals(b,s.snapshot().squad(TeamSide.ATTACKERS,SquadId.ALPHA).leaderUuid());
    }
    @Test void nonLeaderCannotTransferLeadership() {
        var s=service(); UUID a=id(1), b=id(3), c=id(5); admit(s,1); admit(s,2); admit(s,3); admit(s,4); admit(s,5);
        assertEquals(RosterFailure.NOT_SQUAD_LEADER,s.transferLeader(b,c,false,NOW).failure());
    }
    @Test void administratorCanTransferLeadership() {
        var s=service(); UUID a=id(1), b=id(3); admit(s,1); admit(s,2); admit(s,3);
        assertTrue(s.transferLeader(UUID.randomUUID(),b,true,NOW).successful());
    }
    @Test void cannotTransferLeaderToDifferentSquad() {
        var s=service(); for(int i=0;i<10;i++) admit(s,i); UUID target=id(10);
        assertEquals(RosterFailure.TARGET_NOT_IN_SQUAD,s.transferLeader(id(1),target,true,NOW).failure());
    }
    @Test void switchingSquadSucceeds() {
        var s=service(); UUID p=id(1); admit(s,1);
        assertTrue(s.switchSquad(p,SquadId.BRAVO,MatchState.WAITING,NOW.plusSeconds(20),false).successful());
        assertEquals(SquadId.BRAVO,s.assignment(p).orElseThrow().squadId().orElseThrow());
    }
    @Test void failedSwitchKeepsOriginalSquad() {
        var s=service(); UUID p=id(1); admit(s,1);
        var r=s.switchSquad(p,SquadId.ALPHA,MatchState.ENDING,NOW,false);
        assertFalse(r.successful()); assertEquals(SquadId.ALPHA,s.assignment(p).orElseThrow().squadId().orElseThrow());
    }
    @Test void successfulSwitchStartsCooldown() {
        var s=service(); UUID p=id(1); admit(s,1); s.switchSquad(p,SquadId.BRAVO,MatchState.WAITING,NOW,false);
        assertEquals(RosterFailure.SWITCH_COOLDOWN,s.switchSquad(p,SquadId.CHARLIE,MatchState.WAITING,NOW.plusSeconds(1),false).failure());
    }
    @Test void failedSwitchDoesNotStartCooldown() {
        var s=service(); UUID p=id(1); admit(s,1); s.switchSquad(p,SquadId.BRAVO,MatchState.ENDING,NOW,false);
        assertTrue(s.switchSquad(p,SquadId.BRAVO,MatchState.WAITING,NOW,false).successful());
    }
    @Test void leaveSquadKeepsTeam() {
        var s=service(); UUID p=id(1); admit(s,1); assertTrue(s.leaveSquad(p,NOW).successful());
        assertEquals(TeamSide.ATTACKERS,s.assignment(p).orElseThrow().teamSide());
        assertTrue(s.assignment(p).orElseThrow().squadId().isEmpty());
    }
    @Test void playerCanRejoinAfterLeavingSquad() {
        var s=service(); UUID p=id(1); admit(s,1); s.leaveSquad(p,NOW);
        assertTrue(s.switchSquad(p,SquadId.ALPHA,MatchState.WAITING,NOW,false).successful());
    }
    @Test void disconnectCreatesReservation() {
        var s=service(); UUID p=id(1); admit(s,1); assertTrue(s.disconnect(p,NOW.plusSeconds(1)).successful());
        assertEquals(1,s.snapshot().disconnectedReservations());
    }
    @Test void disconnectedAssignmentIsMarkedOffline() {
        var s=service(); UUID p=id(1); admit(s,1); s.disconnect(p,NOW.plusSeconds(1));
        assertFalse(s.assignment(p).orElseThrow().connected());
    }
    @Test void leaderDisconnectIncrementsAutomaticTransferMetric() {
        var s=service(); admit(s,1); admit(s,2); admit(s,3);
        s.disconnect(id(1),NOW.plusSeconds(1));
        assertEquals(1,s.metrics().leaderAutomaticTransfers());
    }
    @Test void disconnectedReservationConsumesTeamCapacity() {
        var s=service(configuration(true,1,1)); UUID p=id(1); admit(s,1); s.disconnect(p,NOW);
        assertEquals(1,s.snapshot().team(TeamSide.ATTACKERS).reservedMembers());
    }
    @Test void disconnectedReservationReleasesSquadCapacity() {
        var s=service(); UUID p=id(1); admit(s,1); s.disconnect(p,NOW);
        assertEquals(0,s.snapshot().squad(TeamSide.ATTACKERS,SquadId.ALPHA).currentMembers());
    }
    @Test void reconnectRestoresTeam() {
        var s=service(); UUID p=id(1); var old=s.admit(p,"P1",NOW,MatchState.WAITING).assignment();
        s.disconnect(p,NOW.plusSeconds(1)); var restored=s.admit(p,"P1",NOW.plusSeconds(2),MatchState.WAITING);
        assertEquals(old.teamSide(),restored.assignment().teamSide()); assertTrue(restored.assignment().restoredAfterReconnect());
    }
    @Test void reconnectRestoresOriginalSquadWhenAvailable() {
        var s=service(); UUID p=id(1); admit(s,1); s.disconnect(p,NOW.plusSeconds(1));
        assertEquals(SquadId.ALPHA,s.admit(p,"P1",NOW.plusSeconds(2),MatchState.WAITING).assignment().squadId().orElseThrow());
    }
    @Test void reconnectUsesSameTeamAlternativeSquadWhenOriginalFull() {
        var s=service(); for(int i=0;i<10;i++) admit(s,i);
        UUID p=id(0); s.disconnect(p,NOW.plusSeconds(20)); admit(s,10);
        var restored=s.admit(p,"P1",NOW.plusSeconds(22),MatchState.WAITING);
        assertEquals(TeamSide.ATTACKERS,restored.assignment().teamSide());
        assertEquals(SquadId.BRAVO,restored.assignment().squadId().orElseThrow());
    }
    @Test void expiredReservationIsRemoved() {
        var s=service(); UUID p=id(1); admit(s,1); s.disconnect(p,NOW);
        assertEquals(1,s.cleanupExpired(NOW.plusSeconds(121)));
        assertTrue(s.assignment(p).isEmpty());
    }
    @Test void reconnectAfterExpiryGetsNewAssignment() {
        var s=service(); UUID p=id(1); admit(s,1); s.disconnect(p,NOW); s.cleanupExpired(NOW.plusSeconds(121));
        assertFalse(s.admit(p,"P1",NOW.plusSeconds(122),MatchState.WAITING).assignment().restoredAfterReconnect());
    }
    @Test void newMatchClearsOldAssignments() {
        var s=service(); admit(s,1); UUID next=UUID.randomUUID(); s.beginMatch(next);
        assertEquals(next,s.snapshot().matchId()); assertEquals(0,s.snapshot().activeAssignments());
    }
    @Test void clearRemovesEverything() {
        var s=service(); admit(s,1); s.disconnect(id(1),NOW); s.clear();
        assertEquals(0,s.snapshot().activeAssignments()); assertEquals(0,s.snapshot().disconnectedReservations());
    }
    @Test void closeMakesRosterClosed() { var s=service(); s.close(); assertEquals(RosterState.CLOSED,s.snapshot().state()); }
    @Test void closedRosterRejectsAdmission() { var s=service(); s.close(); assertEquals(RosterFailure.NOT_MODIFIABLE,admit(s,1).failure()); }
    @Test void samePlayerRelationIsSelf() { var s=service(); admit(s,1); assertEquals(CombatRelation.SELF,s.relation(id(1),id(1))); }
    @Test void sameSquadRelationIsSquadmate() { var s=service(); admit(s,1); admit(s,2); admit(s,3); assertEquals(CombatRelation.SQUADMATE,s.relation(id(1),id(3))); }
    @Test void sameTeamDifferentSquadIsTeammate() {
        var s=service(); for(int i=0;i<=10;i++) admit(s,i);
        assertEquals(CombatRelation.TEAMMATE,s.relation(id(0),id(10)));
    }
    @Test void differentTeamsAreEnemies() { var s=service(); admit(s,1); admit(s,2); assertEquals(CombatRelation.ENEMY,s.relation(id(1),id(2))); }
    @Test void missingAssignmentIsUnknown() { var s=service(); admit(s,1); assertEquals(CombatRelation.UNKNOWN,s.relation(id(1),UUID.randomUUID())); }
    @Test void assignmentBelongsToCurrentMatch() { var s=service(); assertEquals(s.snapshot().matchId(),admit(s,1).assignment().matchId()); }
    @Test void administratorMoveChangesTeam() {
        var s=service(); UUID p=id(1); admit(s,1);
        assertTrue(s.moveTeam(p,TeamSide.DEFENDERS,true,MatchState.WAITING,NOW).successful());
        assertEquals(TeamSide.DEFENDERS,s.assignment(p).orElseThrow().teamSide());
    }
    @Test void playingMoveRequiresForce() {
        var s=service(); UUID p=id(1); admit(s,1);
        assertEquals(RosterFailure.FORCE_REQUIRED,s.moveTeam(p,TeamSide.DEFENDERS,false,MatchState.PLAYING,NOW).failure());
    }
    @Test void forceCannotBreakHardCapacity() {
        var s=service(configuration(true,1,1)); UUID a=id(1), d=id(2); admit(s,1); admit(s,2);
        assertEquals(RosterFailure.TEAM_FULL,s.moveTeam(d,TeamSide.ATTACKERS,true,MatchState.WAITING,NOW).failure());
    }
    @Test void nonForceMoveHonorsMaximumDifference() {
        var s=service(); UUID defender=id(2); admit(s,1); admit(s,2);
        assertEquals(RosterFailure.BALANCE_LIMIT,s.moveTeam(defender,TeamSide.ATTACKERS,false,MatchState.WAITING,NOW).failure());
    }
    @Test void forceMayBreakBalanceDifference() {
        var s=service(); UUID defender=id(2); admit(s,1); admit(s,2);
        assertTrue(s.moveTeam(defender,TeamSide.ATTACKERS,true,MatchState.WAITING,NOW).successful());
    }
    @Test void moveTeamAlsoMovesToTargetTeamSquad() {
        var s=service(); UUID p=id(1); admit(s,1); s.moveTeam(p,TeamSide.DEFENDERS,true,MatchState.WAITING,NOW);
        assertEquals(TeamSide.DEFENDERS,s.assignment(p).orElseThrow().teamSide());
        assertEquals(SquadId.ALPHA,s.assignment(p).orElseThrow().squadId().orElseThrow());
    }
    @Test void rosterRevisionIncrementsOnSuccess() {
        var s=service(); long before=s.snapshot().revision(); admit(s,1); assertEquals(before+1,s.snapshot().revision());
    }
    @Test void rosterRevisionDoesNotIncrementOnFailure() {
        var s=service(); long before=s.snapshot().revision(); s.switchSquad(id(1),SquadId.ALPHA,MatchState.WAITING,NOW,false);
        assertEquals(before,s.snapshot().revision());
    }
    @Test void automaticAssignmentMetricIncrements() { var s=service(); admit(s,1); assertEquals(1,s.metrics().automaticAssignments()); }
    @Test void reconnectMetricIncrements() { var s=service(); admit(s,1); s.disconnect(id(1),NOW); admit(s,1); assertEquals(1,s.metrics().reconnectRestores()); }
    @Test void capacityMetricIncrements() { var s=service(); for(int i=0;i<100;i++) admit(s,i); admit(s,101); assertEquals(1,s.metrics().capacityRejections()); }
    @Test void switchMetricIncrements() { var s=service(); admit(s,1); s.switchSquad(id(1),SquadId.BRAVO,MatchState.WAITING,NOW,false); assertEquals(1,s.metrics().squadSwitches()); }
    @Test void administratorMoveMetricIncrements() { var s=service(); admit(s,1); s.moveTeam(id(1),TeamSide.DEFENDERS,true,MatchState.WAITING,NOW); assertEquals(1,s.metrics().administratorMoves()); }
    @Test void administratorRebalanceMovesPlayerToSmallerTeam() {
        var s=service(); admit(s,1); admit(s,2); s.moveTeam(id(2),TeamSide.ATTACKERS,true,MatchState.WAITING,NOW);
        assertTrue(s.rebalance(id(2),MatchState.WAITING,NOW).successful());
        assertEquals(TeamSide.DEFENDERS,s.assignment(id(2)).orElseThrow().teamSide());
        assertEquals(1,s.metrics().administratorRebalances());
    }
    @Test void staleReservationMetricIncrements() { var s=service(); admit(s,1); s.disconnect(id(1),NOW); s.cleanupExpired(NOW.plusSeconds(121)); assertEquals(1,s.metrics().staleReservationRemovals()); }
    @Test void invariantCheckPassesForValidRoster() { var s=service(); for(int i=0;i<100;i++) admit(s,i); assertTrue(s.checkInvariants().valid()); }
    @Test void emptySquadsHaveNoLeader() { assertTrue(service().snapshot().squads().stream().allMatch(s->s.leaderUuid()==null)); }
    @Test void everyNonEmptySquadHasExactlyOneLeader() {
        var s=service(); for(int i=0;i<100;i++) admit(s,i);
        assertTrue(s.snapshot().squads().stream().filter(q->q.currentMembers()>0).allMatch(q->q.leaderUuid()!=null));
    }
    @Test void noSquadExceedsFiveMembers() {
        var s=service(); for(int i=0;i<100;i++) admit(s,i);
        assertTrue(s.snapshot().squads().stream().allMatch(q->q.currentMembers()<=5));
    }
    @Test void disabledRosterRejectsAdmissions() {
        var s=new DefaultRosterService(UUID.randomUUID(),RosterConfiguration.disabled(),NOW);
        assertEquals(RosterState.DISABLED,s.snapshot().state());
        assertEquals(RosterFailure.DISABLED,admit(s,1).failure());
    }
    @Test void squadIdAliasesAreControlled() {
        assertEquals(SquadId.ALPHA,SquadId.parse("alpha").orElseThrow());
        assertEquals(SquadId.ALPHA,SquadId.parse("阿尔法").orElseThrow());
        assertTrue(SquadId.parse("custom").isEmpty());
    }
    @Test void coreHoldsNoBukkitPlayerReferences() {
        for(var field:DefaultRosterService.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("org.bukkit"));
        }
    }

    private static DefaultRosterService service() {
        return service(configuration(true));
    }

    private static DefaultRosterService service(RosterConfiguration configuration) {
        return new DefaultRosterService(UUID.fromString("00000000-0000-0000-0000-000000000999"),configuration,NOW);
    }

    private static RosterConfiguration configuration(boolean squads) {
        return configuration(squads,50,50);
    }

    private static RosterConfiguration configuration(boolean squads,int attackers,int defenders) {
        return new RosterConfiguration(true,squads,1,true,attackers,defenders,"进攻方","防守方",
            10,5,true,true,true,true,true,true,15,true,120);
    }

    private static RosterOperationResult admit(DefaultRosterService service,int number) {
        return service.admit(id(number),"P"+number,NOW.plusSeconds(number),MatchState.WAITING);
    }

    private static UUID id(int number) {
        return new UUID(0,number+1L);
    }
}
