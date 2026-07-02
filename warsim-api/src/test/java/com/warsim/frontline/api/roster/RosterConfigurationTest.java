package com.warsim.frontline.api.roster;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RosterConfigurationTest {
    @Test void defaultsAreFiftyVersusFifty() {
        var config=RosterConfiguration.defaults(true);
        assertEquals(50,config.attackersMaximumPlayers());
        assertEquals(50,config.defendersMaximumPlayers());
    }
    @Test void rejectsSquadsWithoutTeams() {
        assertThrows(IllegalArgumentException.class,()->new RosterConfiguration(false,true,1,true,50,50,
            "进攻方","防守方",10,5,true,true,true,true,true,true,15,true,120));
    }
    @Test void rejectsTeamCapacityAboveFifty() {
        assertThrows(IllegalArgumentException.class,()->new RosterConfiguration(true,true,1,true,51,50,
            "进攻方","防守方",10,5,true,true,true,true,true,true,15,true,120));
    }
    @Test void rejectsSquadCapacityAboveFive() {
        assertThrows(IllegalArgumentException.class,()->new RosterConfiguration(true,true,1,true,50,50,
            "进攻方","防守方",10,6,true,true,true,true,true,true,15,true,120));
    }
    @Test void disabledConfigurationIsSafe() {
        var config=RosterConfiguration.disabled();
        assertFalse(config.teamsEnabled());
        assertFalse(config.squadsEnabled());
    }
    @Test void rejectsNonDeterministicTeamTieBreak() {
        assertThrows(IllegalArgumentException.class,()->new RosterConfiguration(true,true,1,false,50,50,
            "进攻方","防守方",10,5,true,true,true,true,true,true,15,true,120));
    }
}
