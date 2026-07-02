package com.warsim.frontline.database.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.warsim.frontline.api.database.DatabaseErrorCode;
import com.warsim.frontline.api.database.DatabaseOperationException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedDatabaseExecutorTest {
    @Test
    void propagatesTaskFailureToFuture() {
        try (BoundedDatabaseExecutor executor = new BoundedDatabaseExecutor(1, 100, "test-db")) {
            CompletionException exception = assertThrows(
                CompletionException.class,
                () -> executor.submit(() -> {
                    throw new IllegalStateException("boom");
                }).join()
            );
            assertEquals("boom", exception.getCause().getMessage());
        }
    }

    @Test
    void rejectsWhenQueueIsFullWithoutBlocking() throws Exception {
        try (BoundedDatabaseExecutor executor = new BoundedDatabaseExecutor(1, 100, "test-db")) {
            CountDownLatch release = new CountDownLatch(1);
            executor.submit(() -> {
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            });
            for (int index = 0; index < 100; index++) {
                executor.submit(() -> null);
            }
            CompletionException exception = assertThrows(
                CompletionException.class,
                () -> executor.submit(() -> null).join()
            );
            assertEquals(
                DatabaseErrorCode.QUEUE_FULL,
                ((DatabaseOperationException) exception.getCause()).code()
            );
            release.countDown();
            assertTrue(release.await(1, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void rejectsTasksAfterClose() {
        BoundedDatabaseExecutor executor = new BoundedDatabaseExecutor(1, 100, "test-db");
        executor.close(Duration.ofSeconds(1));
        CompletionException exception = assertThrows(
            CompletionException.class,
            () -> executor.submit(() -> null).join()
        );
        assertEquals(
            DatabaseErrorCode.SHUTTING_DOWN,
            ((DatabaseOperationException) exception.getCause()).code()
        );
    }

    @Test
    void reportsCompletedTaskMetrics() {
        try (BoundedDatabaseExecutor executor = new BoundedDatabaseExecutor(1, 100, "test-db")) {
            executor.submit(() -> 42).join();
            assertEquals(1, executor.metrics().completedTasks());
            assertEquals(0, executor.metrics().queuedTasks());
        }
    }
}
