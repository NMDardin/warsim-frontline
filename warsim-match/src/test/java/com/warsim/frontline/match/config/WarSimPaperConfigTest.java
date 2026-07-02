package com.warsim.frontline.match.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.warsim.frontline.api.node.NodeDescriptor;
import com.warsim.frontline.api.node.NodeType;
import com.warsim.frontline.api.match.MatchConfiguration;
import com.warsim.frontline.api.roster.RosterConfiguration;
import com.warsim.frontline.database.config.DatabaseConfiguration;
import com.warsim.frontline.network.redis.RedisConfiguration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WarSimPaperConfigTest {
    @Test
    void validatesNodeAndNetworkLimits() {
        assertEquals("official-war-01", WarSimPaperConfig.safeDefaults().node().id());
        assertThrows(IllegalArgumentException.class, () -> config("Bad Node", 5000, 8192));
        assertThrows(IllegalArgumentException.class, () -> config("lobby-01", 999, 8192));
        assertThrows(IllegalArgumentException.class, () -> config("lobby-01", 5000, 900));
    }

    @Test void officialDefaultsEnableRoster() {
        assertEquals(true,WarSimPaperConfig.safeDefaults().roster().teamsEnabled());
        assertEquals(true,WarSimPaperConfig.safeDefaults().roster().squadsEnabled());
    }

    @Test void lobbyConfigurationCanDisableRoster() {
        assertEquals(false,config("lobby-01",5000,8192).roster().teamsEnabled());
    }

    private static WarSimPaperConfig config(String node, long timeout, int maximumBytes) {
        return new WarSimPaperConfig(
            new NodeDescriptor(node, NodeType.LOBBY),
            false,
            true,
            "warsim:control",
            timeout,
            maximumBytes,
            Set.of("official-war-01"),
            true,
            true,
            DatabaseConfiguration.disabledDefaults(),
            RedisConfiguration.defaults(),
            MatchConfiguration.defaults(false),
            null,
            RosterConfiguration.disabled(),
            null
        );
    }
}
