package com.warsim.frontline.network.transfer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TransferRequestRegistry implements AutoCloseable {
    private final Map<UUID, PendingTransferRequest> requests = new ConcurrentHashMap<>();
    private final TimeoutScheduler scheduler;

    public TransferRequestRegistry(TimeoutScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public boolean register(
        UUID playerUuid,
        UUID requestId,
        String sourceNodeId,
        String targetNodeId,
        long timeoutMillis,
        Runnable onTimeout
    ) {
        Objects.requireNonNull(onTimeout, "onTimeout");
        PendingTransferRequest pending = new PendingTransferRequest(
            requestId, sourceNodeId, targetNodeId, Cancellable.NO_OP
        );
        PendingTransferRequest existing = requests.putIfAbsent(playerUuid, pending);
        if (existing != null) {
            return false;
        }
        Cancellable timeout = scheduler.schedule(timeoutMillis, () -> {
            AtomicBoolean removed = new AtomicBoolean();
            requests.computeIfPresent(playerUuid, (ignored, current) -> {
                if (!current.requestId().equals(requestId)) {
                    return current;
                }
                removed.set(true);
                return null;
            });
            if (removed.get()) {
                onTimeout.run();
            }
        });
        PendingTransferRequest scheduled = pending.withTimeout(timeout);
        if (!requests.replace(playerUuid, pending, scheduled)) {
            timeout.cancel();
        }
        return true;
    }

    public Optional<PendingTransferRequest> get(UUID playerUuid) {
        return Optional.ofNullable(requests.get(playerUuid));
    }

    public boolean complete(UUID playerUuid, UUID requestId) {
        PendingTransferRequest pending = requests.get(playerUuid);
        if (pending == null || !pending.requestId().equals(requestId)) {
            return false;
        }
        if (requests.remove(playerUuid, pending)) {
            pending.timeout().cancel();
            return true;
        }
        return false;
    }

    public void remove(UUID playerUuid) {
        PendingTransferRequest pending = requests.remove(playerUuid);
        if (pending != null) {
            pending.timeout().cancel();
        }
    }

    public int size() {
        return requests.size();
    }

    @Override
    public void close() {
        requests.values().forEach(pending -> pending.timeout().cancel());
        requests.clear();
    }

    public record PendingTransferRequest(
        UUID requestId,
        String sourceNodeId,
        String targetNodeId,
        Cancellable timeout
    ) {
        public PendingTransferRequest {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(sourceNodeId, "sourceNodeId");
            Objects.requireNonNull(targetNodeId, "targetNodeId");
            Objects.requireNonNull(timeout, "timeout");
        }

        private PendingTransferRequest withTimeout(Cancellable replacement) {
            return new PendingTransferRequest(requestId, sourceNodeId, targetNodeId, replacement);
        }
    }

    @FunctionalInterface
    public interface TimeoutScheduler {
        Cancellable schedule(long delayMillis, Runnable task);
    }

    @FunctionalInterface
    public interface Cancellable {
        Cancellable NO_OP = () -> { };

        void cancel();
    }
}
