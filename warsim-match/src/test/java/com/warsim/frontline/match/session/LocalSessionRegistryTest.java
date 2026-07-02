package com.warsim.frontline.match.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalSessionRegistryTest {
    @Test
    void joinsAndRemovesSession() {
        LocalSessionRegistry registry = registry();
        UUID player = UUID.randomUUID();
        LocalPlayerSession session = registry.join(player);
        assertEquals(SessionState.ACTIVE, session.state());
        assertTrue(registry.leave(player));
        assertEquals(SessionState.CLOSED, session.state());
        assertFalse(registry.find(player).isPresent());
    }

    @Test
    void duplicateSessionClosesPrevious() {
        LocalSessionRegistry registry = registry();
        UUID player = UUID.randomUUID();
        LocalPlayerSession first = registry.join(player);
        LocalPlayerSession second = registry.join(player);
        assertNotSame(first, second);
        assertEquals(SessionState.CLOSED, first.state());
        assertEquals(SessionState.ACTIVE, second.state());
        assertEquals(1, registry.size());
    }

    @Test
    void closeClearsRegistry() {
        LocalSessionRegistry registry = registry();
        registry.join(UUID.randomUUID());
        registry.close();
        assertEquals(0, registry.size());
    }

    private static LocalSessionRegistry registry() {
        return new LocalSessionRegistry(
            Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC),
            "official-war-01"
        );
    }
}
