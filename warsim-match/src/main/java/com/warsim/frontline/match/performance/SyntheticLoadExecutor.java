package com.warsim.frontline.match.performance;

import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.objective.*;
import com.warsim.frontline.api.performance.*;
import com.warsim.frontline.api.roster.*;
import com.warsim.frontline.api.weapon.*;
import com.warsim.frontline.match.objective.DefaultObjectiveService;
import com.warsim.frontline.squad.DefaultRosterService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Callable;

final class SyntheticLoadExecutor {
    private static final UUID SYNTHETIC_MATCH =
        UUID.nameUUIDFromBytes("warsim-frontline-synthetic-match".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    SyntheticLoadResult run(
        UUID runId,
        SyntheticLoadScenario scenario,
        int measurementIterations,
        PerformanceConfiguration configuration,
        java.util.function.BooleanSupplier cancelled
    ) throws Exception {
        Instant startedAt = Instant.now();
        int measurements = Math.min(
            Math.min(measurementIterations, scenario.maximumIterations()),
            configuration.syntheticMaximumIterations()
        );
        int warmup = scenario.warmupIterations();
        ArrayList<Long> samples = new ArrayList<>(measurements);
        long deadline = System.nanoTime() + configuration.syntheticMaximumDurationMillis() * 1_000_000L;
        for (int index = 0; index < warmup; index++) {
            if (cancelled.getAsBoolean() || System.nanoTime() > deadline) {
                return cancelled(runId, scenario, warmup, measurements, samples, startedAt);
            }
            executeOne(scenario.type(), index);
        }
        for (int index = 0; index < measurements; index++) {
            if (cancelled.getAsBoolean() || System.nanoTime() > deadline) {
                return cancelled(runId, scenario, warmup, measurements, samples, startedAt);
            }
            long started = System.nanoTime();
            executeOne(scenario.type(), index);
            samples.add(Math.max(0, System.nanoTime() - started));
        }
        return result(runId, scenario, true, false, null, warmup, measurements, samples, startedAt);
    }

    private SyntheticLoadResult cancelled(
        UUID runId, SyntheticLoadScenario scenario, int warmup, int measurements,
        List<Long> samples, Instant startedAt
    ) {
        return result(runId, scenario, false, true, "cancelled", warmup, measurements, samples, startedAt);
    }

    private void executeOne(SyntheticLoadScenarioType type, int iteration) {
        switch (type) {
            case ROSTER_100_PLAYER_ASSIGNMENT -> rosterScenario(iteration);
            case OBJECTIVE_100_PLAYER_PRESENCE -> objectiveScenario(iteration, 5);
            case WEAPON_100_SHOOTERS_100_CANDIDATES -> weaponScenario(iteration);
            case MIXED_BATTLE_TICK -> {
                rosterScenario(iteration);
                objectiveScenario(iteration, 3);
                weaponScenario(iteration);
            }
        }
    }

    private void rosterScenario(int iteration) {
        DefaultRosterService roster = new DefaultRosterService(
            UUID.nameUUIDFromBytes(("synthetic-roster-" + iteration).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            RosterConfiguration.defaults(true),
            Instant.EPOCH
        );
        for (int i = 0; i < 100; i++) {
            roster.admit(playerUuid(i), "P" + i, Instant.EPOCH.plusMillis(i), MatchState.WAITING);
        }
        roster.admit(playerUuid(100), "P100", Instant.EPOCH.plusMillis(100), MatchState.WAITING);
        roster.checkInvariants();
        roster.close();
    }

    private void objectiveScenario(int iteration, int objectives) {
        ArrayList<ObjectiveDefinition> definitions = new ArrayList<>();
        for (int index = 0; index < objectives; index++) {
            definitions.add(new ObjectiveDefinition(
                new ObjectiveId("p" + index),
                "P" + index,
                new ObjectiveRegion("warsim_load_test", index * 20.0, 64, 0, 12, 8),
                index % 2 == 0 ? ObjectiveOwner.DEFENDERS : ObjectiveOwner.NEUTRAL,
                false,
                new ObjectiveCaptureRules(30, 4, .5, EmptyBehavior.RETURN_TO_OWNER, ContestedBehavior.FREEZE),
                new ObjectiveRewards(0, 0)
            ));
        }
        DefaultObjectiveService service = new DefaultObjectiveService(
            SYNTHETIC_MATCH, 1, new ObjectiveConfiguration(true, 5, definitions),
            Instant.EPOCH, ignored -> {}
        );
        List<ObjectivePlayerPresence> players = syntheticPresence(iteration);
        for (int tick = 0; tick < 20; tick++) {
            service.process(new ObjectivePresenceFrame(
                SYNTHETIC_MATCH, 1, tick * 250_000_000L,
                Instant.EPOCH.plusMillis(tick * 250L), players
            ), MatchState.PLAYING);
        }
        service.close();
    }

    private List<ObjectivePlayerPresence> syntheticPresence(int iteration) {
        ArrayList<ObjectivePlayerPresence> players = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            TeamSide side = i < 50 ? TeamSide.ATTACKERS : TeamSide.DEFENDERS;
            double angle = (Math.PI * 2 * i) / 100.0;
            double radius = 4 + (i % 8);
            players.add(new ObjectivePlayerPresence(
                playerUuid(i), side, "warsim_load_test",
                Math.cos(angle) * radius + (iteration % 5) * 20.0,
                64,
                Math.sin(angle) * radius
            ));
        }
        return players;
    }

    private void weaponScenario(int iteration) {
        WeaponDefinition definition = syntheticWeapon();
        for (int shooter = 0; shooter < 100; shooter++) {
            Vector3 origin = new Vector3(0, 65, shooter * .25);
            Vector3 direction = spread(new Vector3(1, 0, 0), shooter * 31L + iteration);
            List<HitCandidate> candidates = weaponCandidates(shooter);
            HitResult hit = trace(origin, direction, candidates, definition.maximumRange(), OptionalDouble.empty(), 1.0e-6);
            if (hit.hit()) {
                syntheticDamage(definition, hit.distance(), hit.hitZone());
            }
        }
    }

    private static WeaponDefinition syntheticWeapon() {
        return new WeaponDefinition(
            new WeaponId("synthetic_rifle"), "Synthetic Rifle", WeaponCategory.RIFLE,
            FireMode.SEMI_AUTO, "warsim:synthetic_rifle",
            new AmmoConfiguration(5, 30, 2500),
            60, 160, new AccuracyConfiguration(.35),
            new DamageConfiguration(1.75, List.of(
                new RangeDamagePoint(0, 34),
                new RangeDamagePoint(80, 34),
                new RangeDamagePoint(160, 20)
            ))
        );
    }

    private static List<HitCandidate> weaponCandidates(int shooter) {
        ArrayList<HitCandidate> candidates = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            double x = 12 + (i % 10) * 4;
            double z = shooter * .25 + (i / 10) * .8;
            AxisAlignedBox body = new AxisAlignedBox(x - .3, 64, z - .3, x + .3, 65.8, z + .3);
            AxisAlignedBox head = new AxisAlignedBox(x - .25, 65.35, z - .25, x + .25, 65.9, z + .25);
            candidates.add(new HitCandidate(playerUuid(1000 + i), SYNTHETIC_MATCH, "warsim_load_test", body, head));
        }
        return candidates;
    }

    private static HitResult trace(
        Vector3 origin, Vector3 direction, List<HitCandidate> candidates,
        double maxRange, OptionalDouble blockDistance, double epsilon
    ) {
        HitResult best = HitResult.miss();
        for (HitCandidate candidate : candidates) {
            HitResult head = intersect(origin, direction, candidate, candidate.headBox(), HitZone.HEAD, epsilon);
            HitResult body = intersect(origin, direction, candidate, candidate.bodyBox(), HitZone.BODY, epsilon);
            HitResult current = choose(head, body, epsilon);
            if (!current.hit() || current.distance() > maxRange + epsilon) continue;
            if (blockDistance.isPresent() && current.distance() > blockDistance.getAsDouble() + epsilon) continue;
            if (!best.hit() || current.distance() < best.distance() - epsilon
                || Math.abs(current.distance() - best.distance()) <= epsilon
                    && current.targetUuid().compareTo(best.targetUuid()) < 0) {
                best = current;
            }
        }
        return best;
    }

    private static HitResult choose(HitResult head, HitResult body, double epsilon) {
        if (!head.hit()) return body;
        if (!body.hit() || head.distance() <= body.distance() + epsilon) return head;
        return body;
    }

    private static HitResult intersect(
        Vector3 origin, Vector3 direction, HitCandidate candidate, AxisAlignedBox box,
        HitZone zone, double epsilon
    ) {
        double tMin = 0;
        double tMax = Double.POSITIVE_INFINITY;
        double[] o = {origin.x(), origin.y(), origin.z()};
        double[] d = {direction.x(), direction.y(), direction.z()};
        double[] min = {box.minimumX(), box.minimumY(), box.minimumZ()};
        double[] max = {box.maximumX(), box.maximumY(), box.maximumZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) <= epsilon) {
                if (o[axis] < min[axis] - epsilon || o[axis] > max[axis] + epsilon) return HitResult.miss();
                continue;
            }
            double inv = 1.0 / d[axis];
            double t1 = (min[axis] - o[axis]) * inv;
            double t2 = (max[axis] - o[axis]) * inv;
            if (t1 > t2) {
                double tmp = t1; t1 = t2; t2 = tmp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax + epsilon) return HitResult.miss();
        }
        return new HitResult(candidate.targetUuid(), zone, Math.max(0, tMin));
    }

    private static double syntheticDamage(WeaponDefinition definition, double distance, HitZone zone) {
        List<RangeDamagePoint> points = definition.damage().points();
        double damage = points.getLast().damage();
        for (int i = 1; i < points.size(); i++) {
            RangeDamagePoint left = points.get(i - 1);
            RangeDamagePoint right = points.get(i);
            if (distance <= right.distance()) {
                double ratio = (distance - left.distance()) / (right.distance() - left.distance());
                damage = left.damage() + (right.damage() - left.damage()) * ratio;
                break;
            }
        }
        return zone == HitZone.HEAD ? damage * definition.damage().headMultiplier() : damage;
    }

    private static Vector3 spread(Vector3 direction, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        double yaw = (random.nextDouble() - .5) * Math.toRadians(.35);
        double pitch = (random.nextDouble() - .5) * Math.toRadians(.35);
        return new Vector3(
            direction.x() + Math.sin(yaw),
            direction.y() + Math.sin(pitch),
            direction.z()
        ).normalized();
    }

    private static UUID playerUuid(int index) {
        return UUID.nameUUIDFromBytes(("warsim-synthetic-player-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private SyntheticLoadResult result(
        UUID runId, SyntheticLoadScenario scenario, boolean completed, boolean cancelled,
        String failureReason, int warmup, int measurements, List<Long> samples, Instant startedAt
    ) {
        long total = samples.stream().mapToLong(Long::longValue).sum();
        OptionalLong mean = samples.isEmpty() ? OptionalLong.empty()
            : OptionalLong.of(Math.round((double) total / samples.size()));
        OptionalLong max = samples.stream().mapToLong(Long::longValue).max();
        PerformancePercentiles percentiles = samples.size() < 2
            ? PerformancePercentiles.unavailable()
            : percentiles(samples);
        double perSecond = total == 0 ? 0 : samples.size() / (total / 1_000_000_000.0);
        return new SyntheticLoadResult(
            runId, scenario.id(), scenario.type(), completed, cancelled, failureReason,
            warmup, measurements, samples.size(), total, mean, percentiles, max,
            perSecond, startedAt, Instant.now(),
            Map.of("seed", "warsim-frontline-t009", "input", scenario.id())
        );
    }

    private static PerformancePercentiles percentiles(List<Long> samples) {
        ArrayList<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.naturalOrder());
        return new PerformancePercentiles(
            OptionalLong.of(rank(sorted, .50)),
            OptionalLong.of(rank(sorted, .95)),
            OptionalLong.of(rank(sorted, .99))
        );
    }

    private static long rank(List<Long> sorted, double percentile) {
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
