package com.warsim.frontline.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.redis.NodeAvailability;
import org.junit.jupiter.api.Test;

class MatchNodeStatusMapperTest {
    @Test void bootstrapMapsStarting(){assertEquals(NodeAvailability.STARTING,MatchNodeStatusMapper.map(MatchState.BOOTSTRAPPING,true).availability());}
    @Test void waitingAccepts(){assertTrue(MatchNodeStatusMapper.map(MatchState.WAITING,true).acceptingPlayers());}
    @Test void warmupAccepts(){assertTrue(MatchNodeStatusMapper.map(MatchState.WARMUP,true).acceptingPlayers());}
    @Test void playingAllowsConfiguredMidJoin(){assertTrue(MatchNodeStatusMapper.map(MatchState.PLAYING,true).acceptingPlayers());}
    @Test void playingDrainsWhenMidJoinDisabled(){assertEquals(NodeAvailability.DRAINING,MatchNodeStatusMapper.map(MatchState.PLAYING,false).availability());}
    @Test void endingDrains(){assertEquals(NodeAvailability.DRAINING,MatchNodeStatusMapper.map(MatchState.ENDING,true).availability());}
    @Test void resettingDrains(){assertEquals(NodeAvailability.DRAINING,MatchNodeStatusMapper.map(MatchState.RESETTING,true).availability());}
    @Test void stoppingMapsStopping(){assertEquals(NodeAvailability.STOPPING,MatchNodeStatusMapper.map(MatchState.STOPPING,true).availability());}
    @Test void failedUnavailable(){assertFalse(MatchNodeStatusMapper.map(MatchState.FAILED,true).acceptingPlayers());}
}
