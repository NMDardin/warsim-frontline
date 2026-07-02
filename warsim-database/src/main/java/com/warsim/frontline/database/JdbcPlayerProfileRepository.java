package com.warsim.frontline.database;

import com.warsim.frontline.api.database.DatabaseOperationException;
import com.warsim.frontline.api.database.PlayerProfile;
import com.warsim.frontline.database.executor.BoundedDatabaseExecutor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;

public final class JdbcPlayerProfileRepository implements PlayerProfileRepository {
    private static final String COLUMNS =
        "player_uuid, current_name, created_at, last_seen_at, profile_version";

    private final DataSource dataSource;
    private final BoundedDatabaseExecutor executor;
    private final String table;

    public JdbcPlayerProfileRepository(
        DataSource dataSource,
        BoundedDatabaseExecutor executor,
        String schema
    ) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.table = schema + ".player_profiles";
    }

    @Override
    public CompletableFuture<Optional<PlayerProfile>> findByUuid(UUID playerUuid) {
        return executor.submit(() -> findByUuidSync(playerUuid));
    }

    @Override
    public CompletableFuture<PlayerProfile> createIfAbsent(
        UUID playerUuid,
        String currentName,
        Instant now
    ) {
        validateInput(playerUuid, currentName, now);
        return executor.submit(() -> {
            String sql = "INSERT INTO " + table
                + " (player_uuid, current_name, created_at, last_seen_at, profile_version)"
                + " VALUES (?, ?, ?, ?, 1) ON CONFLICT (player_uuid) DO NOTHING";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                bindIdentity(statement, playerUuid, currentName, now);
                statement.executeUpdate();
                return findByUuid(connection, playerUuid).orElseThrow(() ->
                    new DatabaseOperationException(
                        com.warsim.frontline.api.database.DatabaseErrorCode.QUERY_ERROR,
                        "Profile was not available after create"
                    )
                );
            } catch (SQLException exception) {
                throw databaseFailure("Failed to create player profile", exception);
            }
        });
    }

    @Override
    public CompletableFuture<PlayerProfile> updateLastSeen(
        UUID playerUuid,
        String currentName,
        Instant now
    ) {
        validateInput(playerUuid, currentName, now);
        return executor.submit(() -> {
            String sql = "UPDATE " + table
                + " SET current_name = ?, last_seen_at = ? WHERE player_uuid = ? RETURNING " + COLUMNS;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, currentName);
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setObject(3, playerUuid);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new DatabaseOperationException(
                            com.warsim.frontline.api.database.DatabaseErrorCode.QUERY_ERROR,
                            "Player profile does not exist"
                        );
                    }
                    return map(resultSet);
                }
            } catch (SQLException exception) {
                throw databaseFailure("Failed to update player profile", exception);
            }
        });
    }

    @Override
    public CompletableFuture<PlayerProfile> upsertOnJoin(
        UUID playerUuid,
        String currentName,
        Instant now
    ) {
        validateInput(playerUuid, currentName, now);
        return executor.submit(() -> {
            String sql = "INSERT INTO " + table
                + " (player_uuid, current_name, created_at, last_seen_at, profile_version)"
                + " VALUES (?, ?, ?, ?, 1)"
                + " ON CONFLICT (player_uuid) DO UPDATE"
                + " SET current_name = EXCLUDED.current_name, last_seen_at = EXCLUDED.last_seen_at"
                + " RETURNING " + COLUMNS;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                bindIdentity(statement, playerUuid, currentName, now);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new DatabaseOperationException(
                            com.warsim.frontline.api.database.DatabaseErrorCode.QUERY_ERROR,
                            "Upsert returned no player profile"
                        );
                    }
                    return map(resultSet);
                }
            } catch (SQLException exception) {
                throw databaseFailure("Failed to upsert player profile", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Long> countProfiles() {
        return executor.submit(() -> {
            String sql = "SELECT COUNT(player_uuid) FROM " + table;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            } catch (SQLException exception) {
                throw databaseFailure("Failed to count player profiles", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return executor.submit(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT 1");
                 ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            } catch (SQLException exception) {
                throw databaseFailure("Database health check failed", exception);
            }
        });
    }

    private Optional<PlayerProfile> findByUuidSync(UUID playerUuid) {
        try (Connection connection = dataSource.getConnection()) {
            return findByUuid(connection, playerUuid);
        } catch (SQLException exception) {
            throw databaseFailure("Failed to find player profile", exception);
        }
    }

    private Optional<PlayerProfile> findByUuid(Connection connection, UUID playerUuid)
        throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE player_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private static PlayerProfile map(ResultSet resultSet) throws SQLException {
        return new PlayerProfile(
            resultSet.getObject("player_uuid", UUID.class),
            resultSet.getString("current_name"),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("last_seen_at").toInstant(),
            resultSet.getInt("profile_version")
        );
    }

    private static void bindIdentity(
        PreparedStatement statement,
        UUID playerUuid,
        String currentName,
        Instant now
    ) throws SQLException {
        statement.setObject(1, playerUuid);
        statement.setString(2, currentName);
        statement.setTimestamp(3, Timestamp.from(now));
        statement.setTimestamp(4, Timestamp.from(now));
    }

    private static void validateInput(UUID playerUuid, String currentName, Instant now) {
        new PlayerProfile(playerUuid, currentName, now, now, 1);
    }

    private static DatabaseOperationException databaseFailure(String message, SQLException exception) {
        return new DatabaseOperationException(
            SqlExceptionClassifier.classify(exception), message, exception
        );
    }
}
