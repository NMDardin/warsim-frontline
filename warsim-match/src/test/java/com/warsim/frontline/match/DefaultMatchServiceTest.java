package com.warsim.frontline.match;

import static org.junit.jupiter.api.Assertions.*;

import com.warsim.frontline.api.match.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultMatchServiceTest {
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");

    @Test void bootstrapsToWaiting() { assertEquals(MatchState.WAITING, service().snapshot().state()); }
    @Test void waitingCannotJumpToPlaying() { assertFalse(MatchState.WAITING.canTransitionTo(MatchState.PLAYING)); }
    @Test void playingCannotJumpToWaiting() { assertFalse(MatchState.PLAYING.canTransitionTo(MatchState.WAITING)); }
    @Test void revisionIncrementsDuringBootstrap() { assertEquals(1, service().snapshot().lifecycleRevision()); }
    @Test void belowMinimumStaysWaiting() { var s=service(); s.tick(1_000_000_000L,NOW); assertEquals(MatchState.WAITING,s.snapshot().state()); }
    @Test void minimumPlayersStartsWarmup() { var s=service(); join(s,40); s.tick(1,NOW); assertEquals(MatchState.WARMUP,s.snapshot().state()); }
    @Test void warmupDropCancels() { var s=service(); join(s,40); s.tick(1,NOW); s.participantLeft(ids[0],NOW); s.tick(2,NOW); assertEquals(MatchState.WAITING,s.snapshot().state()); }
    @Test void warmupCompletesIntoPlaying() { var s=service(); join(s,40); s.tick(1,NOW); s.tick(seconds(61),NOW.plusSeconds(61)); assertEquals(MatchState.PLAYING,s.snapshot().state()); }
    @Test void roundTimeoutEntersEnding() { var s=playing(); s.tick(seconds(2800),NOW.plusSeconds(2800)); assertEquals(MatchState.ENDING,s.snapshot().state()); }
    @Test void endingCompletesIntoResetting() { var s=playing(); s.end(MatchEndReason.ADMIN_STOP,"x"); s.tick(seconds(80),NOW.plusSeconds(80)); assertEquals(MatchState.RESETTING,s.snapshot().state()); }
    @Test void successfulResetCreatesNewMatch() { var s=playing(); UUID old=s.snapshot().matchId(); s.end(MatchEndReason.ADMIN_STOP,"x"); s.tick(seconds(80),NOW.plusSeconds(80)); s.tick(seconds(81),NOW.plusSeconds(81)); assertNotEquals(old,s.snapshot().matchId()); }
    @Test void successfulResetReturnsWaiting() { var s=playing(); s.end(MatchEndReason.ADMIN_STOP,"x"); s.tick(seconds(80),NOW.plusSeconds(80)); s.tick(seconds(81),NOW.plusSeconds(81)); assertEquals(MatchState.WAITING,s.snapshot().state()); }
    @Test void resetClearsParticipants() { var s=playing(); s.end(MatchEndReason.ADMIN_STOP,"x"); s.tick(seconds(80),NOW.plusSeconds(80)); s.tick(seconds(81),NOW.plusSeconds(81)); assertEquals(0,s.snapshot().currentPlayers()); }
    @Test void resetFailureEntersFailed() { var s=service(new FailingReset()); s.reset(); s.tick(1,NOW); assertEquals(MatchState.FAILED,s.snapshot().state()); }
    @Test void failedRejectsPlayers() { var s=service(new FailingReset()); s.reset(); s.tick(1,NOW); assertFalse(s.participantJoined(UUID.randomUUID(),"Player",NOW).accepted()); }
    @Test void recoverCreatesNewMatch() { var s=service(new FailingReset()); UUID old=s.snapshot().matchId(); s.reset(); s.tick(1,NOW); s.recover(); assertNotEquals(old,s.snapshot().matchId()); }
    @Test void recoverReturnsWaiting() { var s=service(new FailingReset()); s.reset(); s.tick(1,NOW); s.recover(); assertEquals(MatchState.WAITING,s.snapshot().state()); }
    @Test void repeatedStartRejected() { var s=service(); assertTrue(s.start(true).accepted()); assertFalse(s.start(true).accepted()); }
    @Test void normalStartChecksMinimum() { assertFalse(service().start(false).accepted()); }
    @Test void forceStartEntersWarmup() { var s=service(); assertTrue(s.start(true).accepted()); assertEquals(MatchState.WARMUP,s.snapshot().state()); }
    @Test void forceWarmupIgnoresMinimumDrop() { var s=service(); s.start(true); s.tick(seconds(10),NOW.plusSeconds(10)); assertEquals(MatchState.WARMUP,s.snapshot().state()); }
    @Test void endWarmupReturnsWaiting() { var s=service(); s.start(true); s.end(MatchEndReason.ADMIN_STOP,"x"); assertEquals(MatchState.WAITING,s.snapshot().state()); }
    @Test void endPlayingEntersEnding() { var s=playing(); s.end(MatchEndReason.ADMIN_STOP,"x"); assertEquals(MatchState.ENDING,s.snapshot().state()); }
    @Test void resetPlayingRejected() { assertFalse(playing().reset().accepted()); }
    @Test void firstParticipantJoins() { assertTrue(service().participantJoined(UUID.randomUUID(),"Player",NOW).accepted()); }
    @Test void duplicateParticipantIsIdempotent() { var s=service(); UUID id=UUID.randomUUID(); s.participantJoined(id,"Player",NOW); s.participantJoined(id,"Player2",NOW); assertEquals(1,s.snapshot().currentPlayers()); }
    @Test void participantLeaveRemoves() { var s=service(); UUID id=UUID.randomUUID(); s.participantJoined(id,"Player",NOW); assertTrue(s.participantLeft(id,NOW)); assertEquals(0,s.snapshot().currentPlayers()); }
    @Test void repeatedLeaveIsSafe() { assertFalse(service().participantLeft(UUID.randomUUID(),NOW)); }
    @Test void midRoundJoinAllowed() { assertTrue(playing().participantJoined(UUID.randomUUID(),"Late",NOW).accepted()); }
    @Test void capacityIsEnforced() { var s=service(config(1,2,true,true)); join(s,2); assertFalse(s.participantJoined(UUID.randomUUID(),"Third",NOW).accepted()); }
    @Test void staleTaskIsCounted() { var s=service(); s.rejectStaleTask(UUID.randomUUID(),99); assertEquals(1,s.metrics().staleTasksRejected()); }
    @Test void stopWaitingReachesStopped() { var s=service(); s.stop(); assertEquals(MatchState.STOPPED,s.snapshot().state()); }
    @Test void stopPlayingReachesStopped() { var s=playing(); s.stop(); assertEquals(MatchState.STOPPED,s.snapshot().state()); }
    @Test void repeatedStopIsIdempotent() { var s=service(); s.stop(); s.stop(); assertEquals(MatchState.STOPPED,s.snapshot().state()); }
    @Test void transitionsAreCounted() { assertTrue(service().metrics().stateTransitions() >= 1); }
    @Test void warmupCancellationIsCounted() { var s=service(); join(s,40); s.tick(1,NOW); s.participantLeft(ids[0],NOW); s.tick(2,NOW); assertEquals(1,s.metrics().warmupCancellations()); }
    @Test void administratorStartsCounted() { var s=service(); s.start(true); assertEquals(1,s.metrics().administratorStarts()); }
    @Test void administratorEndsCounted() { var s=playing(); s.end(MatchEndReason.ADMIN_STOP,"x"); assertEquals(1,s.metrics().administratorEnds()); }
    @Test void historyIsBoundedToTen() { var s=service(); for(int i=0;i<12;i++){s.reset();s.tick(i+1,NOW.plusSeconds(i+1));} assertEquals(10,s.history().size()); }
    @Test void autoCycleFalseCreatesManualWaiting() { var s=service(config(1,100,true,false)); s.start(true); s.tick(seconds(61),NOW.plusSeconds(61)); s.end(MatchEndReason.ADMIN_STOP,"x"); s.tick(seconds(80),NOW.plusSeconds(80)); s.tick(seconds(81),NOW.plusSeconds(81)); assertTrue(s.snapshot().manualWaiting()); }
    @Test void manualWaitingDoesNotAutoStart() { var s=service(config(1,100,true,false)); s.reset(); s.tick(1,NOW); s.participantJoined(UUID.randomUUID(),"P",NOW); s.tick(10,NOW); assertEquals(MatchState.WAITING,s.snapshot().state()); }
    @Test void eventFailureDoesNotBlockNextListener() { var s=service(); AtomicInteger called=new AtomicInteger(); s.subscribe(e->{throw new RuntimeException();},false); s.subscribe(e->called.incrementAndGet(),false); s.start(true); assertTrue(called.get()>0); }
    @Test void closedListenerStopsReceiving() { var s=service(); AtomicInteger called=new AtomicInteger(); AutoCloseable h=s.subscribe(e->called.incrementAndGet(),false); try{h.close();}catch(Exception e){fail(e);} s.start(true); assertEquals(0,called.get()); }
    @Test void midRoundJoinCanBeDisabled() { var s=service(config(1,100,true,true,false)); s.start(true); s.tick(seconds(61),NOW.plusSeconds(61)); assertFalse(s.participantJoined(UUID.randomUUID(),"Late",NOW).accepted()); }
    @Test void initializationFailureEntersFailed() { var s=service(); s.failInitialization("bad config"); assertEquals(MatchState.FAILED,s.snapshot().state()); }
    @Test void resetTimeoutEntersFailed() { var s=service(c -> new CompletableFuture<>()); s.reset(); s.tick(seconds(31),NOW.plusSeconds(31)); assertEquals(MatchState.FAILED,s.snapshot().state()); }
    @Test void staleResetCompletionCannotReplaceMatch() {
        var reset = new DeferredReset();
        var s=service(reset);
        s.reset();
        UUID expected=s.snapshot().matchId();
        s.stop();
        reset.future.complete(MatchResetResult.success());
        assertEquals(expected,s.snapshot().matchId());
        assertEquals(1,s.metrics().staleTasksRejected());
    }
    @Test void administratorReasonIsSanitizedAndBounded() { var s=playing(); s.end(MatchEndReason.ADMIN_STOP,"x\u0000"+"a".repeat(200)); assertFalse(s.snapshot().endSummary().contains("\u0000")); assertEquals(128,s.snapshot().endSummary().length()); }
    @Test void eventsPreserveRegistrationOrder() { var s=service(); var order=new java.util.ArrayList<Integer>(); s.subscribe(e->order.add(1),false); s.subscribe(e->order.add(2),false); s.start(true); assertEquals(java.util.List.of(1,2),order); }
    @Test void matchScopedListenerIsRemovedAfterReset() { var s=service(); AtomicInteger count=new AtomicInteger(); s.subscribe(e->count.incrementAndGet(),true); s.reset(); s.tick(1,NOW); int after=count.get(); s.start(true); assertEquals(after,count.get()); }
    @Test void participantModelContainsNoBukkitTypes() { for(var field:MatchParticipant.class.getDeclaredFields()) assertFalse(field.getType().getName().startsWith("org.bukkit")); }

    private static UUID[] ids = new UUID[100];
    private static void join(DefaultMatchService s,int count){for(int i=0;i<count;i++){ids[i]=UUID.randomUUID();s.participantJoined(ids[i],"P"+i,NOW);}}
    private static long seconds(long value){return value*1_000_000_000L;}
    private static DefaultMatchService playing(){var s=service();s.start(true);s.tick(seconds(61),NOW.plusSeconds(61));return s;}
    private static DefaultMatchService service(){return service(MatchResetService.noOp());}
    private static DefaultMatchService service(MatchResetService reset){return new DefaultMatchService("official-war-01",config(40,100,true,true),reset,Runnable::run,NOW,0);}
    private static DefaultMatchService service(MatchConfiguration c){return new DefaultMatchService("official-war-01",c,MatchResetService.noOp(),Runnable::run,NOW,0);}
    private static MatchConfiguration config(int min,int max,boolean autoStart,boolean autoCycle){return config(min,max,autoStart,autoCycle,true);}
    private static MatchConfiguration config(int min,int max,boolean autoStart,boolean autoCycle,boolean midJoin){return new MatchConfiguration(true,"frontline_offensive",min,max,true,midJoin,60,2700,15,30,autoStart,autoCycle,true,java.util.List.of(60,30,10,5,4,3,2,1));}
    private static final class FailingReset implements MatchResetService { public CompletableFuture<MatchResetResult> reset(MatchResetContext c){return CompletableFuture.completedFuture(MatchResetResult.failure("failed"));}}
    private static final class DeferredReset implements MatchResetService {
        final CompletableFuture<MatchResetResult> future = new CompletableFuture<>();
        public CompletableFuture<MatchResetResult> reset(MatchResetContext c){return future;}
    }
}
