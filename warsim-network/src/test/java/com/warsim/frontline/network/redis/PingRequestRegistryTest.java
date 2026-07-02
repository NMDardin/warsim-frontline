package com.warsim.frontline.network.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class PingRequestRegistryTest {
    @Test void matchingPongCompletesAndRemovesRequest() {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try (PingRequestRegistry registry = new PingRequestRegistry(scheduler)) {
            UUID id = UUID.randomUUID();
            Instant start = Instant.parse("2026-06-21T00:00:00Z");
            var future = registry.register(id, start, Duration.ofSeconds(1));
            assertTrue(registry.complete(id, start.plusMillis(25)));
            assertEquals(25, future.join().toMillis());
            assertEquals(0, registry.size());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test void pingTimeoutRemovesRequest() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try (PingRequestRegistry registry = new PingRequestRegistry(scheduler)) {
            var future = registry.register(UUID.randomUUID(), Instant.now(), Duration.ofMillis(20));
            Thread.sleep(50);
            assertThrows(CompletionException.class, future::join);
            assertEquals(0, registry.size());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test void publishFailureCancelsAndRemovesRequest() {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try (PingRequestRegistry registry = new PingRequestRegistry(scheduler)) {
            UUID id = UUID.randomUUID();
            var future = registry.register(id, Instant.now(), Duration.ofSeconds(1));
            IllegalStateException failure = new IllegalStateException("publish failed");

            assertTrue(registry.fail(id, failure));
            CompletionException completion = assertThrows(CompletionException.class, future::join);
            assertEquals(failure, completion.getCause());
            assertEquals(0, registry.size());
        } finally {
            scheduler.shutdownNow();
        }
    }
}
