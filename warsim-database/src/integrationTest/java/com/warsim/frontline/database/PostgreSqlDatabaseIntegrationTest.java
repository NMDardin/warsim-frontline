package com.warsim.frontline.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.warsim.frontline.api.database.PlayerProfile;
import com.warsim.frontline.database.config.DatabaseConfiguration;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgreSqlDatabaseIntegrationTest {
    private static PostgreSQLContainer postgres;
    private static PostgreSqlDatabaseService service;
    private static UUID playerUuid;
    private static Instant createdAt;

    @BeforeAll
    static void startPostgreSql() {
        Assumptions.assumeTrue(
            DockerClientFactory.instance().isDockerAvailable(),
            "Docker is unavailable; PostgreSQL integration tests were skipped"
        );
        postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("warsim_frontline")
            .withUsername("warsim_frontline")
            .withPassword("integration-secret");
        postgres.start();
        DatabaseConfiguration configuration = DatabaseConfiguration.productionDefaults()
            .withJdbcUrl(postgres.getJdbcUrl())
            .withCredentials(postgres.getUsername(), postgres.getPassword());
        service = new PostgreSqlDatabaseService(configuration);
        service.start().join();
        playerUuid = UUID.randomUUID();
        createdAt = Instant.parse("2026-06-21T00:00:00Z");
    }

    @AfterAll
    static void stopPostgreSql() {
        if (service != null) {
            service.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @Order(1)
    void flywayV1MigrationSucceeds() throws Exception {
        try (var connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
             var statement = connection.prepareStatement(
                 "SELECT version FROM warsim_frontline.frontline_schema_history WHERE success = TRUE"
             );
             var resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertEquals("1", resultSet.getString(1));
        }
    }

    @Test
    @Order(2)
    void playerProfilesTableExists() throws Exception {
        try (var connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
             var statement = connection.prepareStatement(
                 "SELECT to_regclass('warsim_frontline.player_profiles')"
             );
             var resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertNotNull(resultSet.getString(1));
        }
    }

    @Test
    @Order(3)
    void upsertCreatesProfile() {
        PlayerProfile profile = service.upsertOnJoin(playerUuid, "Frontline_01", createdAt).join();
        assertEquals(playerUuid, profile.playerUuid());
        assertEquals(createdAt, profile.createdAt());
    }

    @Test
    @Order(4)
    void repeatedUpsertPreservesCreatedAt() {
        PlayerProfile profile = service.upsertOnJoin(
            playerUuid, "Frontline_02", createdAt.plusSeconds(60)
        ).join();
        assertEquals(createdAt, profile.createdAt());
    }

    @Test
    @Order(5)
    void repeatedUpsertUpdatesCurrentName() {
        PlayerProfile profile = service.findByUuid(playerUuid).join().orElseThrow();
        assertEquals("Frontline_02", profile.currentName());
    }

    @Test
    @Order(6)
    void repeatedUpsertUpdatesLastSeenAt() {
        PlayerProfile profile = service.findByUuid(playerUuid).join().orElseThrow();
        assertEquals(createdAt.plusSeconds(60), profile.lastSeenAt());
    }

    @Test
    @Order(7)
    void findByUuidReadsProfile() {
        assertTrue(service.findByUuid(playerUuid).join().isPresent());
        assertFalse(service.findByUuid(UUID.randomUUID()).join().isPresent());
    }

    @Test
    @Order(8)
    void countProfilesReturnsExactCount() {
        assertEquals(1, service.countProfiles().join());
    }

    @Test
    @Order(9)
    void preparedStatementsHandleAllowedSpecialNameCharacters() {
        UUID second = UUID.randomUUID();
        PlayerProfile profile = service.upsertOnJoin(
            second, "Select_DROP_1", createdAt.plusSeconds(120)
        ).join();
        assertEquals("Select_DROP_1", profile.currentName());
        assertEquals(2, service.countProfiles().join());
    }

    @Test
    @Order(10)
    void closingServiceClosesConnectionPool() {
        service.close();
        assertTrue(service.isPoolClosed());
    }
}
