package com.warsim.frontline.database.executor;

import com.warsim.frontline.api.database.DatabaseErrorCode;
import com.warsim.frontline.api.database.DatabaseOperationException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class BoundedDatabaseExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public BoundedDatabaseExecutor(int threads, int queueCapacity, String threadPrefix) {
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(
            threads,
            threads,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            runnable -> Thread.ofPlatform()
                .name(threadPrefix + "-" + sequence.incrementAndGet())
                .daemon(false)
                .unstarted(runnable),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        if (!accepting.get()) {
            return CompletableFuture.failedFuture(new DatabaseOperationException(
                DatabaseErrorCode.SHUTTING_DOWN, "Database executor is shutting down"
            ));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.get());
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException exception) {
            DatabaseErrorCode code = accepting.get()
                ? DatabaseErrorCode.QUEUE_FULL
                : DatabaseErrorCode.SHUTTING_DOWN;
            future.completeExceptionally(new DatabaseOperationException(
                code, code == DatabaseErrorCode.QUEUE_FULL
                    ? "Database executor queue is full"
                    : "Database executor is shutting down",
                exception
            ));
        }
        return future;
    }

    public ExecutorMetrics metrics() {
        return new ExecutorMetrics(
            executor.getActiveCount(),
            executor.getQueue().size(),
            executor.getCompletedTaskCount()
        );
    }

    public void close(Duration timeout) {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(Math.min(1000, timeout.toMillis()), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(5));
    }
}
