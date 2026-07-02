package com.warsim.frontline.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.warsim.frontline.api.database.DatabaseErrorCode;
import com.warsim.frontline.api.database.DatabaseOperationException;
import com.warsim.frontline.api.database.DatabaseState;
import com.warsim.frontline.database.config.DatabaseConfiguration;
import java.util.concurrent.CompletionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostgreSqlDatabaseServiceTest {
    @Test
    void disabledServiceStartsDisabledWithoutCreatingPool() {
        PostgreSqlDatabaseService service = new PostgreSqlDatabaseService(
            DatabaseConfiguration.disabledDefaults()
        );
        service.start().join();
        assertEquals(DatabaseState.DISABLED, service.state());
        service.close();
        service.close();
        assertEquals(DatabaseState.DISABLED, service.state());
    }

    @Test
    void invalidEnabledConfigurationStartsFailed() {
        PostgreSqlDatabaseService service = new PostgreSqlDatabaseService(
            DatabaseConfiguration.productionDefaults()
        );
        CompletionException exception = assertThrows(CompletionException.class, () -> service.start().join());
        assertEquals(
            DatabaseErrorCode.CONFIGURATION_ERROR,
            ((DatabaseOperationException) exception.getCause()).code()
        );
        assertEquals(DatabaseState.FAILED, service.state());
        service.close();
    }

    @Test
    void repeatedStartIsSafeForDisabledService() {
        PostgreSqlDatabaseService service = new PostgreSqlDatabaseService(
            DatabaseConfiguration.disabledDefaults()
        );
        service.start().join();
        service.start().join();
        assertEquals(DatabaseState.DISABLED, service.state());
        service.close();
    }

    @Test
    void unavailableProfileOperationReturnsFailedFutureInsteadOfThrowingSynchronously() {
        PostgreSqlDatabaseService service = new PostgreSqlDatabaseService(
            DatabaseConfiguration.disabledDefaults()
        );
        var future = service.upsertOnJoin(UUID.randomUUID(), "Player_01", Instant.now());
        CompletionException exception = assertThrows(CompletionException.class, future::join);
        assertEquals(
            DatabaseErrorCode.CONNECTION_ERROR,
            ((DatabaseOperationException) exception.getCause()).code()
        );
        service.close();
    }
}
