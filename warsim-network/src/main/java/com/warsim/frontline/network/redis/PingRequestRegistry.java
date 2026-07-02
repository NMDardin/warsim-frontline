package com.warsim.frontline.network.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class PingRequestRegistry implements AutoCloseable {
    private final ScheduledExecutorService scheduler;
    private final Map<UUID, Pending> requests = new ConcurrentHashMap<>();

    PingRequestRegistry(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    CompletableFuture<Duration> register(UUID messageId, Instant startedAt, Duration timeout) {
        CompletableFuture<Duration> future = new CompletableFuture<>();
        requests.put(messageId, new Pending(startedAt, future));
        scheduler.schedule(() -> {
            Pending removed = requests.remove(messageId);
            if (removed != null) {
                removed.future().completeExceptionally(new TimeoutException("Redis ping timed out"));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return future;
    }

    boolean complete(UUID correlationId, Instant completedAt) {
        Pending pending = requests.remove(correlationId);
        if (pending == null) {
            return false;
        }
        pending.future().complete(Duration.between(pending.startedAt(), completedAt));
        return true;
    }

    boolean fail(UUID messageId, Throwable failure) {
        Pending pending = requests.remove(messageId);
        if (pending == null) {
            return false;
        }
        pending.future().completeExceptionally(failure);
        return true;
    }

    int size() {
        return requests.size();
    }

    @Override
    public void close() {
        requests.values().forEach(pending ->
            pending.future().completeExceptionally(new IllegalStateException("Redis service stopped"))
        );
        requests.clear();
    }

    private record Pending(Instant startedAt, CompletableFuture<Duration> future) {
    }
}
