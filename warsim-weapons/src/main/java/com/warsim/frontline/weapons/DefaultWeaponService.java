package com.warsim.frontline.weapons;

import com.warsim.frontline.api.roster.CombatRelation;
import com.warsim.frontline.api.weapon.*;
import java.time.Instant;
import java.util.*;

public final class DefaultWeaponService implements WeaponService {
    private final WeaponConfiguration configuration;
    private final Map<WeaponId, WeaponDefinition> definitions;
    private final WeaponStateRegistry registry;
    private final HitscanBallisticsService ballistics;
    private final DefaultDamageCalculator damage;
    private final SpreadCalculator spread;
    private final MutableWeaponMetrics metrics = new MutableWeaponMetrics();
    private final ShotEventDispatcher events;
    private WeaponSystemState state;

    public DefaultWeaponService(WeaponConfiguration configuration) {
        this(configuration, new WeaponStateRegistry(), new HitscanBallisticsService(),
            new DefaultDamageCalculator(), new SpreadCalculator(),
            configuration.enabled() ? WeaponSystemState.ACTIVE : WeaponSystemState.DISABLED);
    }

    public DefaultWeaponService(WeaponConfiguration configuration, WeaponSystemState initialState) {
        this(configuration, new WeaponStateRegistry(), new HitscanBallisticsService(),
            new DefaultDamageCalculator(), new SpreadCalculator(), initialState);
    }

    DefaultWeaponService(
        WeaponConfiguration configuration,
        WeaponStateRegistry registry,
        HitscanBallisticsService ballistics,
        DefaultDamageCalculator damage,
        SpreadCalculator spread,
        WeaponSystemState initialState
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        if (initialState != WeaponSystemState.ACTIVE
            && initialState != WeaponSystemState.DISABLED
            && initialState != WeaponSystemState.FAILED) {
            throw new IllegalArgumentException("Invalid initial weapon state");
        }
        this.registry = registry;
        this.ballistics = ballistics;
        this.damage = damage;
        this.spread = spread;
        this.events = new ShotEventDispatcher(
            ignored -> metrics.listenerFailures.incrementAndGet()
        );
        LinkedHashMap<WeaponId, WeaponDefinition> loaded = new LinkedHashMap<>();
        configuration.definitions().forEach(value -> loaded.put(value.weaponId(), value));
        definitions = Collections.unmodifiableMap(loaded);
        state = initialState;
    }

    @Override public synchronized WeaponSystemState state() { return state; }
    @Override public List<WeaponDefinition> definitions() { return List.copyOf(definitions.values()); }
    @Override public Optional<WeaponDefinition> definition(WeaponId id) {
        return Optional.ofNullable(definitions.get(id));
    }
    @Override public Optional<WeaponRuntimeState> runtimeState(UUID p, UUID m, WeaponId w) {
        return registry.find(p, m, w);
    }

    @Override
    public synchronized WeaponOperationResult canFire(
        UUID player, UUID match, WeaponId weaponId, long now
    ) {
        if (state != WeaponSystemState.ACTIVE) {
            return reject(WeaponFailureReason.DISABLED, "Weapon system is unavailable");
        }
        WeaponDefinition definition = definitions.get(weaponId);
        if (definition == null) return reject(WeaponFailureReason.INVALID_ITEM, "Unknown weapon");
        WeaponStateRegistry.MutableState runtime =
            registry.getOrCreate(player, match, definition);
        if (runtime.reload == ReloadState.RELOADING) {
            return reject(WeaponFailureReason.RELOADING, "Reloading");
        }
        if (runtime.magazine == 0) {
            metrics.emptyRejections.incrementAndGet();
            return reject(WeaponFailureReason.EMPTY, "Magazine is empty");
        }
        if (now < runtime.nextShot) {
            metrics.cooldownRejections.incrementAndGet();
            return reject(WeaponFailureReason.COOLDOWN, "Fire rate cooldown");
        }
        return new WeaponOperationResult(true, WeaponFailureReason.NONE, "Ready to fire",
            runtime.snapshot());
    }

    @Override
    public synchronized Vector3 spreadDirection(
        WeaponId weaponId, Vector3 direction, long deterministicSeed
    ) {
        WeaponDefinition definition = definitions.get(weaponId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown weapon");
        }
        return spread.apply(
            direction, definition.accuracy().hipSpreadDegrees(),
            new SplittableRandom(deterministicSeed)::nextDouble
        );
    }

    @Override
    public synchronized ShotResult fire(
        ShotContext context,
        java.util.function.Function<UUID, CombatRelation> relationResolver
    ) {
        return fire(context, relationResolver, WeaponDamagePolicy.failClosed());
    }

    @Override
    public synchronized ShotResult fire(
        ShotContext context,
        java.util.function.Function<UUID, CombatRelation> relationResolver,
        WeaponDamagePolicy damagePolicy
    ) {
        long started = System.nanoTime();
        metrics.shotsRequested.incrementAndGet();
        damagePolicy = damagePolicy == null ? WeaponDamagePolicy.failClosed() : damagePolicy;
        ShotRequest request = context.request();
        WeaponOperationResult allowed = canFire(
            request.shooterUuid(), request.matchId(), request.weaponId(),
            request.monotonicNanos()
        );
        if (!allowed.successful()) {
            metrics.shotsRejected.incrementAndGet();
            return finish(started, new ShotResult(
                request, rejectedOutcome(allowed.reason()), allowed.reason(),
                HitResult.miss(), 0, allowed.state()
            ));
        }
        WeaponDefinition definition = definitions.get(request.weaponId());
        WeaponStateRegistry.MutableState runtime = registry.getOrCreate(
            request.shooterUuid(), request.matchId(), definition
        );
        Vector3 direction = request.normalizedDirection();
        metrics.candidatesSampled.addAndGet(context.candidates().size());
        metrics.rayTests.addAndGet((long) context.candidates().size() * 2);
        HitResult hit = ballistics.trace(
            new Ray(request.origin(), direction), context.candidates(),
            definition.maximumRange(), context.blockHitDistance(), configuration.epsilon()
        );
        runtime.magazine--;
        runtime.shots++;
        runtime.nextShot = request.monotonicNanos() + definition.shotIntervalNanos();
        runtime.revision++;
        metrics.shotsFired.incrementAndGet();
        metrics.lastShotAt = Instant.now();

        ShotOutcome outcome;
        double requestedDamage = 0;
        CombatRelation relation = CombatRelation.UNKNOWN;
        boolean friendly = false;
        if (!hit.hit()) {
            if (context.blockHitDistance().isPresent()
                && context.blockHitDistance().getAsDouble() <= definition.maximumRange()) {
                outcome = ShotOutcome.FIRED_BLOCKED;
                metrics.blockObstructions.incrementAndGet();
            } else {
                outcome = ShotOutcome.FIRED_MISS;
                metrics.misses.incrementAndGet();
            }
        } else {
            relation = hit.targetType() == HitTargetType.VEHICLE
                ? CombatRelation.ENEMY : relationResolver.apply(hit.targetUuid());
            if (relation == null) relation = CombatRelation.UNKNOWN;
            friendly = hit.targetType() != HitTargetType.VEHICLE
                && (relation == CombatRelation.SQUADMATE || relation == CombatRelation.TEAMMATE);
            DamageResult calculated = damage.calculate(new DamageRequest(
                definition, hit.distance(),
                hit.targetType() == HitTargetType.VEHICLE ? HitZone.BODY : hit.hitZone(),
                relation,
                damagePolicy.friendlyFireEnabled(), damagePolicy.selfDamageEnabled()
            ));
            outcome = calculated.outcome();
            requestedDamage = calculated.damage();
            if (!calculated.allowed()) {
                metrics.friendlyHitsBlocked.incrementAndGet();
            } else if (hit.hitZone() == HitZone.HEAD) {
                metrics.headHits.incrementAndGet();
            } else {
                metrics.bodyHits.incrementAndGet();
            }
        }
        ShotResult result = new ShotResult(
            request, outcome, WeaponFailureReason.NONE, hit,
            requestedDamage, runtime.snapshot(), relation, friendly
        );
        Instant occurredAt = Instant.now();
        events.publish(new WeaponFiredEvent(
            request.matchId(), request.shotId(), request.weaponId(),
            request.shooterUuid(), outcome, occurredAt
        ));
        if (outcome == ShotOutcome.FIRED_MISS) {
            events.publish(new ShotMissedEvent(
                request.matchId(), request.shotId(), request.weaponId(),
                request.shooterUuid(), occurredAt
            ));
        } else if (outcome == ShotOutcome.FIRED_BLOCKED) {
            events.publish(new ShotBlockedEvent(
                request.matchId(), request.shotId(), request.weaponId(),
                request.shooterUuid(), occurredAt
            ));
        } else if (hit.hit()) {
            events.publish(new ShotHitEvent(
                request.matchId(), request.shotId(), request.weaponId(),
                request.shooterUuid(), hit.targetUuid(), hit.hitZone(),
                hit.distance(), occurredAt
            ));
        }
        return finish(started, result);
    }

    @Override
    public synchronized WeaponOperationResult startReload(
        UUID player, UUID match, WeaponId weaponId, long now
    ) {
        WeaponDefinition definition = definitions.get(weaponId);
        if (state != WeaponSystemState.ACTIVE || definition == null) {
            return reject(WeaponFailureReason.INVALID_STATE, "Weapon system is unavailable");
        }
        var runtime = registry.getOrCreate(player, match, definition);
        if (runtime.reload == ReloadState.RELOADING) {
            return reject(WeaponFailureReason.RELOADING, "Reloading");
        }
        if (runtime.magazine >= definition.ammo().magazineSize()) {
            return reject(WeaponFailureReason.FULL_MAGAZINE, "Magazine is full");
        }
        if (runtime.reserve == 0) {
            return reject(WeaponFailureReason.NO_RESERVE, "No reserve ammo");
        }
        runtime.reload = ReloadState.RELOADING;
        runtime.reloadStarted = now;
        runtime.reloadCompletes = now + definition.ammo().reloadMillis() * 1_000_000L;
        runtime.revision++;
        metrics.reloadsStarted.incrementAndGet();
        events.publish(new WeaponReloadEvent(
            match, new ShotId(new UUID(0, 0)), weaponId, player,
            ReloadState.RELOADING, Instant.now()
        ));
        return new WeaponOperationResult(true, WeaponFailureReason.NONE, "Reload started",
            runtime.snapshot());
    }

    @Override
    public synchronized int completeReloads(long now) {
        int completed = 0;
        for (var runtime : registry.mutableStates()) {
            if (runtime.reload != ReloadState.RELOADING || now < runtime.reloadCompletes) continue;
            WeaponDefinition definition = definitions.get(runtime.weapon);
            int moved = Math.min(
                definition.ammo().magazineSize() - runtime.magazine, runtime.reserve
            );
            runtime.magazine += moved;
            runtime.reserve -= moved;
            runtime.reload = ReloadState.READY;
            runtime.reloadStarted = 0;
            runtime.reloadCompletes = 0;
            runtime.revision++;
            completed++;
            metrics.reloadsCompleted.incrementAndGet();
            events.publish(new WeaponReloadEvent(
                runtime.match, new ShotId(new UUID(0, 0)), runtime.weapon,
                runtime.player, ReloadState.READY, Instant.now()
            ));
        }
        return completed;
    }

    @Override
    public synchronized boolean cancelReload(UUID player, UUID match, WeaponId weaponId) {
        var optional = registry.find(player, match, weaponId);
        if (optional.isEmpty() || optional.get().reloadState() != ReloadState.RELOADING) return false;
        var runtime = registry.getOrCreate(player, match, definitions.get(weaponId));
        runtime.reload = ReloadState.READY;
        runtime.reloadStarted = 0;
        runtime.reloadCompletes = 0;
        runtime.revision++;
        metrics.reloadsCancelled.incrementAndGet();
        events.publish(new WeaponReloadEvent(
            match, new ShotId(new UUID(0, 0)), weaponId, player,
            ReloadState.READY, Instant.now()
        ));
        return true;
    }

    @Override public void clearPlayer(UUID player) { registry.clearPlayer(player); }
    @Override public void clearWeapon(UUID player, UUID match, WeaponId weapon) {
        registry.clearWeapon(player, match, weapon);
    }
    @Override public void clearMatch(UUID match) { registry.clearMatch(match); }

    public synchronized void restoreRuntimeState(WeaponRuntimeState snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        registry.restore(snapshot);
    }

    @Override
    public synchronized WeaponOperationResult refill(UUID player, UUID match, WeaponId weaponId) {
        WeaponDefinition definition = definitions.get(weaponId);
        if (definition == null) return reject(WeaponFailureReason.INVALID_ITEM, "Unknown weapon");
        var runtime = registry.getOrCreate(player, match, definition);
        runtime.magazine = definition.ammo().magazineSize();
        runtime.reserve = definition.ammo().reserveAmmo();
        runtime.reload = ReloadState.READY;
        runtime.reloadStarted = 0;
        runtime.reloadCompletes = 0;
        runtime.revision++;
        return new WeaponOperationResult(true, WeaponFailureReason.NONE, "Ammo refilled",
            runtime.snapshot());
    }

    @Override public WeaponMetricsSnapshot metrics() {
        return metrics.snapshot(definitions.size(), registry.size());
    }

    public void recordDamageApplication() { metrics.damageApplications.incrementAndGet(); }
    @Override
    public void recordDamageApplied(ShotResult result) {
        recordDamageApplication();
        events.publish(new ShotDamageAppliedEvent(
            result.request().matchId(), result.request().shotId(),
            result.request().weaponId(), result.request().shooterUuid(),
            result.hit().targetUuid(), result.requestedDamage(), Instant.now()
        ));
    }

    @Override
    public void recordKill(
        UUID matchId, ShotId shotId, WeaponId weaponId,
        UUID shooterUuid, UUID targetUuid
    ) {
        metrics.kills.incrementAndGet();
        events.publish(new ShotKillEvent(
            matchId, shotId, weaponId, shooterUuid, targetUuid, Instant.now()
        ));
    }

    @Override
    public AutoCloseable subscribe(WeaponEventListener listener) {
        return events.subscribe(listener);
    }
    public void recordInvalidItem() { metrics.invalidItemsRejected.incrementAndGet(); }
    public void recordStaleShot() { metrics.staleShotsRejected.incrementAndGet(); }
    public void recordCandidateTruncation() { metrics.candidateLimitTruncations.incrementAndGet(); }

    @Override
    public synchronized void close() {
        if (state == WeaponSystemState.CLOSED) return;
        registry.clear();
        events.close();
        state = WeaponSystemState.CLOSED;
    }

    private WeaponOperationResult reject(WeaponFailureReason reason, String message) {
        return WeaponOperationResult.rejected(reason, message);
    }

    private ShotResult finish(long started, ShotResult result) {
        long elapsed = Math.max(0, System.nanoTime() - started);
        metrics.lastShotProcessingNanos.set(elapsed);
        metrics.maximumShotProcessingNanos.accumulateAndGet(elapsed, Math::max);
        return result;
    }

    private static ShotOutcome rejectedOutcome(WeaponFailureReason reason) {
        return switch (reason) {
            case COOLDOWN -> ShotOutcome.REJECTED_COOLDOWN;
            case EMPTY -> ShotOutcome.REJECTED_EMPTY;
            case RELOADING -> ShotOutcome.REJECTED_RELOADING;
            case INVALID_ITEM -> ShotOutcome.REJECTED_INVALID_ITEM;
            default -> ShotOutcome.REJECTED_INVALID_STATE;
        };
    }
}
