package com.warsim.frontline.network.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TransferRequestRegistryTest {
    @Test
    void rejectsDuplicateAndRemovesCompletedRequest() {
        FakeScheduler scheduler = new FakeScheduler();
        TransferRequestRegistry registry = new TransferRequestRegistry(scheduler);
        UUID player = UUID.randomUUID();
        UUID request = UUID.randomUUID();

        assertTrue(registry.register(player, request, "lobby-01", "official-war-01", 5000, () -> { }));
        assertFalse(registry.register(
            player, UUID.randomUUID(), "lobby-01", "official-war-01", 5000, () -> { }
        ));
        assertTrue(registry.complete(player, request));
        assertEquals(0, registry.size());
        assertTrue(scheduler.tasks.getFirst().cancelled.get());
    }

    @Test
    void timeoutRemovesRequest() {
        FakeScheduler scheduler = new FakeScheduler();
        TransferRequestRegistry registry = new TransferRequestRegistry(scheduler);
        AtomicBoolean timedOut = new AtomicBoolean();
        UUID player = UUID.randomUUID();

        registry.register(
            player, UUID.randomUUID(), "lobby-01", "official-war-01", 5000,
            () -> timedOut.set(true)
        );
        scheduler.tasks.getFirst().task.run();

        assertTrue(timedOut.get());
        assertEquals(0, registry.size());
    }

    @Test
    void closeClearsAndCancelsRequests() {
        FakeScheduler scheduler = new FakeScheduler();
        TransferRequestRegistry registry = new TransferRequestRegistry(scheduler);
        registry.register(
            UUID.randomUUID(), UUID.randomUUID(), "lobby-01", "official-war-01", 5000, () -> { }
        );

        registry.close();

        assertEquals(0, registry.size());
        assertTrue(scheduler.tasks.getFirst().cancelled.get());
    }

    private static final class FakeScheduler implements TransferRequestRegistry.TimeoutScheduler {
        private final List<FakeTask> tasks = new ArrayList<>();

        @Override
        public TransferRequestRegistry.Cancellable schedule(long delayMillis, Runnable task) {
            FakeTask fakeTask = new FakeTask(task);
            tasks.add(fakeTask);
            return () -> fakeTask.cancelled.set(true);
        }
    }

    private record FakeTask(Runnable task, AtomicBoolean cancelled) {
        private FakeTask(Runnable task) {
            this(task, new AtomicBoolean());
        }
    }
}
