package com.warsim.frontline.api.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerProfileTest {
    private static final Instant CREATED = Instant.parse("2026-06-21T00:00:00Z");

    @Test
    void acceptsValidProfile() {
        UUID uuid = UUID.randomUUID();
        PlayerProfile profile = new PlayerProfile(uuid, "Frontline_01", CREATED, CREATED.plusSeconds(5), 1);
        assertEquals(uuid, profile.playerUuid());
        assertEquals("Frontline_01", profile.currentName());
    }

    @Test
    void rejectsInvalidPlayerNames() {
        UUID uuid = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> profile(uuid, ""));
        assertThrows(IllegalArgumentException.class, () -> profile(uuid, "name-with-dash"));
        assertThrows(IllegalArgumentException.class, () -> profile(uuid, "a".repeat(17)));
    }

    @Test
    void rejectsInvalidProfileVersion() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new PlayerProfile(UUID.randomUUID(), "Player", CREATED, CREATED, 0)
        );
    }

    @Test
    void rejectsLastSeenBeforeCreation() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new PlayerProfile(UUID.randomUUID(), "Player", CREATED, CREATED.minusSeconds(1), 1)
        );
    }

    private static PlayerProfile profile(UUID uuid, String name) {
        return new PlayerProfile(uuid, name, CREATED, CREATED, 1);
    }
}
