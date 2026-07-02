package com.warsim.frontline.weapons;

import static org.junit.jupiter.api.Assertions.*;
import com.warsim.frontline.api.roster.CombatRelation;
import com.warsim.frontline.api.weapon.*;
import java.util.*;
import org.junit.jupiter.api.*;

class DefaultWeaponServiceTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID MATCH = new UUID(0, 2);
    private static final WeaponId WEAPON = new WeaponId("test");
    private DefaultWeaponService service;

    @BeforeEach void setUp() {
        service = new DefaultWeaponService(configuration());
    }

    @Test void firstUseCreatesState() {
        assertTrue(service.canFire(PLAYER, MATCH, WEAPON, 0).successful());
        assertTrue(service.runtimeState(PLAYER, MATCH, WEAPON).isPresent());
    }

    @Test void stateBindsMatch() {
        service.canFire(PLAYER, MATCH, WEAPON, 0);
        assertTrue(service.runtimeState(PLAYER, UUID.randomUUID(), WEAPON).isEmpty());
    }

    @Test void shotConsumesOneRound() {
        ShotResult result = fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        assertEquals(4, result.state().magazineAmmo());
    }

    @Test void missConsumesOneRound() {
        assertEquals(ShotOutcome.FIRED_MISS,
            fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY).outcome());
        assertEquals(4, service.runtimeState(PLAYER, MATCH, WEAPON).orElseThrow().magazineAmmo());
    }

    @Test void cooldownRejectsWithoutConsumption() {
        fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        ShotResult second = fire(1, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        assertEquals(ShotOutcome.REJECTED_COOLDOWN, second.outcome());
        assertEquals(4, service.runtimeState(PLAYER, MATCH, WEAPON).orElseThrow().magazineAmmo());
    }

    @Test void boundaryAllowsNextShot() {
        fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        long next = service.runtimeState(PLAYER, MATCH, WEAPON).orElseThrow()
            .nextAllowedShotAtNanos();
        assertTrue(fire(next, List.of(), OptionalDouble.empty(),
            ignored -> CombatRelation.ENEMY).fired());
    }

    @Test void lagDoesNotFireMoreThanOnce() {
        fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        fire(10_000_000_000L, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        assertEquals(3, service.runtimeState(PLAYER, MATCH, WEAPON).orElseThrow().magazineAmmo());
    }

    @Test void emptyMagazineRejects() {
        for (int i = 0; i < 5; i++) fire(i * 1_000_000_000L, List.of(),
            OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        assertEquals(WeaponFailureReason.EMPTY,
            service.canFire(PLAYER, MATCH, WEAPON, 10_000_000_000L).reason());
    }

    @Test void fullMagazineCannotReload() {
        assertEquals(WeaponFailureReason.FULL_MAGAZINE,
            service.startReload(PLAYER, MATCH, WEAPON, 0).reason());
    }

    @Test void reloadMovesOnlyMissingAmmo() {
        fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        service.startReload(PLAYER, MATCH, WEAPON, 2_000_000_000L);
        service.completeReloads(2_200_000_000L);
        WeaponRuntimeState state = service.runtimeState(PLAYER, MATCH, WEAPON).orElseThrow();
        assertEquals(5, state.magazineAmmo());
        assertEquals(9, state.reserveAmmo());
    }

    @Test void cancelReloadDoesNotConsumeReserve() {
        fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        service.startReload(PLAYER, MATCH, WEAPON, 2_000_000_000L);
        service.cancelReload(PLAYER, MATCH, WEAPON);
        WeaponRuntimeState state = service.runtimeState(PLAYER, MATCH, WEAPON).orElseThrow();
        assertEquals(4, state.magazineAmmo());
        assertEquals(10, state.reserveAmmo());
    }

    @Test void resetClearsMatchStates() {
        service.canFire(PLAYER, MATCH, WEAPON, 0);
        service.clearMatch(MATCH);
        assertTrue(service.runtimeState(PLAYER, MATCH, WEAPON).isEmpty());
    }

    @Test void newMatchStartsFresh() {
        fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        UUID next = UUID.randomUUID();
        service.canFire(PLAYER, next, WEAPON, 0);
        assertEquals(5, service.runtimeState(PLAYER, next, WEAPON).orElseThrow().magazineAmmo());
    }

    @Test void zeroSpreadLeavesDirection() {
        Vector3 direction = new Vector3(1, 0, 0);
        assertEquals(direction, service.spreadDirection(WEAPON, direction, 1));
    }

    @Test void fixedSeedIsStable() {
        DefaultWeaponService spread = new DefaultWeaponService(configuration(.5));
        Vector3 first = spread.spreadDirection(WEAPON, new Vector3(1, 0, 0), 42);
        Vector3 second = spread.spreadDirection(WEAPON, new Vector3(1, 0, 0), 42);
        assertEquals(first, second);
        assertEquals(1, first.lengthSquared(), 1E-9);
    }

    @Test void bodyHit() {
        ShotResult result = fire(0, List.of(candidate(false)), OptionalDouble.empty(),
            ignored -> CombatRelation.ENEMY);
        assertEquals(ShotOutcome.FIRED_BODY_HIT, result.outcome());
    }

    @Test void headHit() {
        ShotResult result = fire(0, List.of(candidate(true)), OptionalDouble.empty(),
            ignored -> CombatRelation.ENEMY);
        assertEquals(ShotOutcome.FIRED_HEAD_HIT, result.outcome());
    }

    @Test void friendlyHitConsumesAmmoButDoesNotDamage() {
        ShotResult result = fire(0, List.of(candidate(false)), OptionalDouble.empty(),
            ignored -> CombatRelation.TEAMMATE);
        assertEquals(ShotOutcome.FRIENDLY_BLOCKED, result.outcome());
        assertEquals(0, result.requestedDamage());
        assertEquals(4, result.state().magazineAmmo());
    }

    @Test void unknownRelationIsSafe() {
        assertEquals(ShotOutcome.FRIENDLY_BLOCKED,
            fire(0, List.of(candidate(false)), OptionalDouble.empty(),
                ignored -> CombatRelation.UNKNOWN).outcome());
    }

    @Test void obstructionProducesBlocked() {
        assertEquals(ShotOutcome.FIRED_BLOCKED,
            fire(0, List.of(candidate(false)), OptionalDouble.of(2),
                ignored -> CombatRelation.ENEMY).outcome());
    }

    @Test void refillRestoresDefaults() {
        fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        service.refill(PLAYER, MATCH, WEAPON);
        WeaponRuntimeState state = service.runtimeState(PLAYER, MATCH, WEAPON).orElseThrow();
        assertEquals(5, state.magazineAmmo());
        assertEquals(10, state.reserveAmmo());
    }

    @Test void playerClearIsIsolated() {
        UUID other = UUID.randomUUID();
        service.canFire(PLAYER, MATCH, WEAPON, 0);
        service.canFire(other, MATCH, WEAPON, 0);
        service.clearPlayer(PLAYER);
        assertTrue(service.runtimeState(other, MATCH, WEAPON).isPresent());
    }

    @Test void closeClearsAndRejects() {
        service.canFire(PLAYER, MATCH, WEAPON, 0);
        service.close();
        assertEquals(WeaponSystemState.CLOSED, service.state());
        assertEquals(WeaponFailureReason.DISABLED,
            service.canFire(PLAYER, MATCH, WEAPON, 0).reason());
    }

    @Test void configurationFailureCanStartInFailedState() {
        DefaultWeaponService failed = new DefaultWeaponService(
            WeaponConfiguration.disabled(), WeaponSystemState.FAILED
        );

        assertEquals(WeaponSystemState.FAILED, failed.state());
    }

    @Test void listenerFailuresAreIsolated() {
        service.subscribe(event -> { throw new IllegalStateException("test"); });
        fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        assertTrue(service.metrics().listenerFailures() > 0);
    }

    @Test void metricsCountRequestsHitsAndMisses() {
        fire(0, List.of(), OptionalDouble.empty(), ignored -> CombatRelation.ENEMY);
        fire(2_000_000_000L, List.of(candidate(false)), OptionalDouble.empty(),
            ignored -> CombatRelation.ENEMY);
        WeaponMetricsSnapshot metrics = service.metrics();
        assertEquals(2, metrics.shotsRequested());
        assertEquals(1, metrics.misses());
        assertEquals(1, metrics.bodyHits());
    }

    @Test void oneHundredCandidatesAreBoundedAndProcessed() {
        ArrayList<HitCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            candidates.add(new HitCandidate(
                new UUID(0, i + 10), MATCH, "world",
                new AxisAlignedBox(5 + i, -1, -1, 5.5 + i, .5, 1),
                new AxisAlignedBox(5 + i, .5, -1, 5.5 + i, 1, 1)
            ));
        }
        ShotResult result = fire(0, candidates, OptionalDouble.empty(),
            ignored -> CombatRelation.ENEMY);
        assertTrue(result.hit().hit());
        assertEquals(200, service.metrics().rayTests());
    }

    private ShotResult fire(
        long now, List<HitCandidate> candidates, OptionalDouble block,
        java.util.function.Function<UUID, CombatRelation> relation
    ) {
        ShotRequest request = new ShotRequest(
            new ShotId(UUID.randomUUID()), MATCH, 1, PLAYER, WEAPON, "world",
            new Vector3(0, 0, 0), new Vector3(1, 0, 0), now, 1
        );
        return service.fire(new ShotContext(request, candidates, block), relation);
    }

    private static HitCandidate candidate(boolean head) {
        AxisAlignedBox body = head
            ? new AxisAlignedBox(5, -2, -1, 6, -1, 1)
            : new AxisAlignedBox(5, -1, -1, 6, 1, 1);
        AxisAlignedBox headBox = head
            ? new AxisAlignedBox(5, -1, -1, 6, 1, 1)
            : new AxisAlignedBox(5, 2, -1, 6, 3, 1);
        return new HitCandidate(UUID.randomUUID(), MATCH, "world", body, headBox);
    }

    private static WeaponConfiguration configuration() { return configuration(0); }
    private static WeaponConfiguration configuration(double spread) {
        WeaponDefinition definition = new WeaponDefinition(
            WEAPON, "Test", WeaponCategory.RIFLE, FireMode.SEMI_AUTO,
            "warsim:test", new AmmoConfiguration(5, 10, 100), 60, 100,
            new AccuracyConfiguration(spread),
            new DamageConfiguration(2, List.of(
                new RangeDamagePoint(0, 20), new RangeDamagePoint(100, 10)
            ))
        );
        return new WeaponConfiguration(
            true, false, false, 100, 200, 250, .25, 1E-6, 2,
            List.of(definition)
        );
    }
}
