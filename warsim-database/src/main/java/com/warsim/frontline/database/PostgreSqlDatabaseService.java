package com.warsim.frontline.database;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.warsim.frontline.api.database.DatabaseErrorCode;
import com.warsim.frontline.api.database.DatabaseHealth;
import com.warsim.frontline.api.database.DatabaseMetricsSnapshot;
import com.warsim.frontline.api.database.DatabaseOperationException;
import com.warsim.frontline.api.database.DatabaseService;
import com.warsim.frontline.api.database.DatabaseState;
import com.warsim.frontline.api.database.PlayerProfile;
import com.warsim.frontline.database.config.DatabaseConfiguration;
import com.warsim.frontline.database.config.DatabaseConfigurationValidator;
import com.warsim.frontline.database.executor.BoundedDatabaseExecutor;
import com.warsim.frontline.database.executor.ExecutorMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class PostgreSqlDatabaseService implements DatabaseService {
    private final DatabaseConfiguration configuration;
    private final HikariDataSourceFactory dataSourceFactory;
    private final FlywayMigrationService migrationService;
    private final Clock clock;
    private final AtomicReference<DatabaseState> state;
    private final AtomicReference<DatabaseHealth> health;
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile CompletableFuture<Void> startFuture;
    private volatile BoundedDatabaseExecutor executor;
    private volatile HikariDataSource dataSource;
    private volatile JdbcPlayerProfileRepository repository;
    private volatile boolean migrated;
    private volatile int consecutiveHealthFailures;

    public PostgreSqlDatabaseService(DatabaseConfiguration configuration) {
        this(configuration, new HikariDataSourceFactory(), new FlywayMigrationService(), Clock.systemUTC());
    }

    PostgreSqlDatabaseService(
        DatabaseConfiguration configuration,
        HikariDataSourceFactory dataSourceFactory,
        FlywayMigrationService migrationService,
        Clock clock
    ) {
        this.configuration = configuration;
        this.dataSourceFactory = dataSourceFactory;
        this.migrationService = migrationService;
        this.clock = clock;
        DatabaseState initial = configuration.enabled() ? DatabaseState.CREATED : DatabaseState.DISABLED;
        this.state = new AtomicReference<>(initial);
        this.health = new AtomicReference<>(DatabaseHealth.initial(initial));
    }

    @Override
    public boolean enabled() {
        return configuration.enabled();
    }

    @Override
    public String schema() {
        return configuration.schema();
    }

    @Override
    public DatabaseState state() {
        return state.get();
    }

    @Override
    public DatabaseHealth health() {
        return health.get();
    }

    @Override
    public DatabaseMetricsSnapshot metrics() {
        BoundedDatabaseExecutor currentExecutor = executor;
        ExecutorMetrics executorMetrics = currentExecutor == null
            ? new ExecutorMetrics(0, 0, 0)
            : currentExecutor.metrics();
        HikariDataSource currentDataSource = dataSource;
        HikariPoolMXBean pool = currentDataSource == null ? null : currentDataSource.getHikariPoolMXBean();
        return new DatabaseMetricsSnapshot(
            pool == null ? 0 : pool.getActiveConnections(),
            pool == null ? 0 : pool.getIdleConnections(),
            pool == null ? 0 : pool.getThreadsAwaitingConnection(),
            executorMetrics.activeTasks(),
            executorMetrics.queuedTasks(),
            executorMetrics.completedTasks()
        );
    }

    @Override
    public synchronized CompletableFuture<Void> start() {
        if (!configuration.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        if (startFuture != null) {
            return startFuture;
        }
        try {
            DatabaseConfigurationValidator.validate(configuration);
        } catch (DatabaseOperationException exception) {
            setFailure(DatabaseState.FAILED, exception.code(), "数据库配置无效");
            startFuture = CompletableFuture.failedFuture(exception);
            return startFuture;
        }
        transition(DatabaseState.STARTING);
        executor = new BoundedDatabaseExecutor(
            configuration.executorThreads(),
            configuration.executorQueueCapacity(),
            "warsim-db"
        );
        startFuture = executor.submit(() -> {
            dataSource = dataSourceFactory.create(configuration);
            repository = new JdbcPlayerProfileRepository(dataSource, executor, configuration.schema());
            performHealthAndMigration();
            return null;
        });
        startFuture.whenComplete((ignored, failure) -> {
            if (failure != null && state.get() != DatabaseState.FAILED) {
                Throwable cause = unwrap(failure);
                DatabaseErrorCode code = cause instanceof DatabaseOperationException databaseFailure
                    ? databaseFailure.code()
                    : DatabaseErrorCode.UNKNOWN;
                DatabaseState failureState = code == DatabaseErrorCode.MIGRATION_ERROR
                    ? DatabaseState.FAILED
                    : DatabaseState.UNAVAILABLE;
                setFailure(failureState, code, "数据库启动失败");
            }
        });
        return startFuture;
    }

    @Override
    public CompletableFuture<DatabaseHealth> healthCheck() {
        if (!configuration.enabled()) {
            return CompletableFuture.completedFuture(health.get());
        }
        BoundedDatabaseExecutor currentExecutor = executor;
        if (currentExecutor == null || closed.get()) {
            return CompletableFuture.failedFuture(new DatabaseOperationException(
                DatabaseErrorCode.SHUTTING_DOWN, "Database service is not accepting health checks"
            ));
        }
        if (!healthCheckRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(health.get());
        }
        return currentExecutor.submit(() -> {
            try {
                performHealthAndMigration();
                return health.get();
            } finally {
                healthCheckRunning.set(false);
            }
        });
    }

    private void performHealthAndMigration() {
        try {
            if (!rawHealthCheck()) {
                throw new DatabaseOperationException(
                    DatabaseErrorCode.CONNECTION_ERROR, "SELECT 1 returned an invalid result"
                );
            }
            if (configuration.migrationsEnabled() && !migrated) {
                transition(DatabaseState.MIGRATING);
                try {
                    migrationService.migrate(dataSource, configuration.schema());
                    migrated = true;
                } catch (RuntimeException exception) {
                    throw new DatabaseOperationException(
                        DatabaseErrorCode.MIGRATION_ERROR, "Flyway migration failed", exception
                    );
                }
            }
            consecutiveHealthFailures = 0;
            state.set(DatabaseState.HEALTHY);
            health.set(new DatabaseHealth(
                DatabaseState.HEALTHY, DatabaseErrorCode.NONE, Instant.now(clock), "数据库连接正常"
            ));
        } catch (DatabaseOperationException exception) {
            consecutiveHealthFailures++;
            DatabaseState failureState = exception.code() == DatabaseErrorCode.MIGRATION_ERROR
                ? DatabaseState.FAILED
                : consecutiveHealthFailures == 1 ? DatabaseState.DEGRADED : DatabaseState.UNAVAILABLE;
            setFailure(failureState, exception.code(), "数据库健康检查失败");
            throw exception;
        }
    }

    private boolean rawHealthCheck() {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT 1");
             var resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getInt(1) == 1;
        } catch (java.sql.SQLException exception) {
            throw new DatabaseOperationException(
                SqlExceptionClassifier.classify(exception), "Database health check failed", exception
            );
        }
    }

    @Override
    public CompletableFuture<Optional<PlayerProfile>> findByUuid(UUID playerUuid) {
        return withReadyRepository(repository -> repository.findByUuid(playerUuid));
    }

    @Override
    public CompletableFuture<PlayerProfile> createIfAbsent(
        UUID playerUuid, String currentName, Instant now
    ) {
        return withReadyRepository(
            repository -> repository.createIfAbsent(playerUuid, currentName, now)
        );
    }

    @Override
    public CompletableFuture<PlayerProfile> updateLastSeen(
        UUID playerUuid, String currentName, Instant now
    ) {
        return withReadyRepository(
            repository -> repository.updateLastSeen(playerUuid, currentName, now)
        );
    }

    @Override
    public CompletableFuture<PlayerProfile> upsertOnJoin(
        UUID playerUuid, String currentName, Instant now
    ) {
        return withReadyRepository(
            repository -> repository.upsertOnJoin(playerUuid, currentName, now)
        );
    }

    @Override
    public CompletableFuture<Long> countProfiles() {
        return withReadyRepository(JdbcPlayerProfileRepository::countProfiles);
    }

    private <T> CompletableFuture<T> withReadyRepository(
        Function<JdbcPlayerProfileRepository, CompletableFuture<T>> operation
    ) {
        if (state.get() != DatabaseState.HEALTHY || repository == null || closed.get()) {
            return CompletableFuture.failedFuture(new DatabaseOperationException(
                closed.get() ? DatabaseErrorCode.SHUTTING_DOWN : DatabaseErrorCode.CONNECTION_ERROR,
                "Database is not healthy"
            ));
        }
        try {
            return operation.apply(repository);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public synchronized void close() {
        if (!configuration.enabled() || !closed.compareAndSet(false, true)) {
            return;
        }
        DatabaseState current = state.get();
        if (current != DatabaseState.STOPPED) {
            state.set(DatabaseState.STOPPING);
        }
        BoundedDatabaseExecutor currentExecutor = executor;
        if (currentExecutor != null) {
            currentExecutor.close(Duration.ofMillis(configuration.shutdownTimeoutMillis()));
        }
        HikariDataSource currentDataSource = dataSource;
        if (currentDataSource != null) {
            currentDataSource.close();
        }
        state.set(DatabaseState.STOPPED);
        health.set(new DatabaseHealth(
            DatabaseState.STOPPED, DatabaseErrorCode.NONE, Instant.now(clock), "数据库服务已关闭"
        ));
    }

    public boolean isPoolClosed() {
        return dataSource == null || dataSource.isClosed();
    }

    private void transition(DatabaseState target) {
        DatabaseState current = state.get();
        if (current == target) {
            return;
        }
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid database transition " + current + " -> " + target);
        }
        state.set(target);
    }

    private void setFailure(DatabaseState target, DatabaseErrorCode code, String summary) {
        state.set(target);
        health.set(new DatabaseHealth(target, code, Instant.now(clock), summary));
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
    }
}
