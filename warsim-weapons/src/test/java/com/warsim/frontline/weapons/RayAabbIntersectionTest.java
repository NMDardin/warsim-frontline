package com.warsim.frontline.weapons;

import static org.junit.jupiter.api.Assertions.*;
import com.warsim.frontline.api.weapon.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RayAabbIntersectionTest {
    private static final AxisAlignedBox BOX = new AxisAlignedBox(5, -1, -1, 6, 1, 1);

    @ParameterizedTest
    @CsvSource({
        "0,0,0, 1,0,0, 5",
        "5.5,0,0, 1,0,0, 0",
        "0,1,0, 1,0,0, 5",
        "0,0,1, 1,0,0, 5",
        "10,0,0, -1,0,0, 4",
        "5,0,0, 0,1,0, 0"
    })
    void stableIntersections(
        double ox, double oy, double oz, double dx, double dy, double dz, double expected
    ) {
        double actual = RayAabbIntersection.intersect(
            new Ray(new Vector3(ox, oy, oz), new Vector3(dx, dy, dz)), BOX, 1E-6
        ).orElseThrow();
        assertEquals(expected, actual, 1E-6);
    }

    @ParameterizedTest
    @CsvSource({
        "0,2,0, 1,0,0",
        "0,0,2, 1,0,0",
        "10,0,0, 1,0,0",
        "0,2,0, 0,1,0",
        "0,0,0, 0,1,0"
    })
    void misses(
        double ox, double oy, double oz, double dx, double dy, double dz
    ) {
        assertTrue(RayAabbIntersection.intersect(
            new Ray(new Vector3(ox, oy, oz), new Vector3(dx, dy, dz)), BOX, 1E-6
        ).isEmpty());
    }

    @Test void nearestTargetWins() {
        UUID far = new UUID(0, 2);
        UUID near = new UUID(0, 1);
        HitResult result = new HitscanBallisticsService().trace(
            new Ray(new Vector3(0, 0, 0), new Vector3(1, 0, 0)),
            List.of(candidate(far, 10), candidate(near, 5)), 100,
            OptionalDouble.empty(), 1E-6
        );
        assertEquals(near, result.targetUuid());
    }

    @Test void uuidBreaksEqualDistanceTie() {
        UUID first = new UUID(0, 1);
        UUID second = new UUID(0, 2);
        HitResult result = new HitscanBallisticsService().trace(
            new Ray(new Vector3(0, 0, 0), new Vector3(1, 0, 0)),
            List.of(candidate(second, 5), candidate(first, 5)), 100,
            OptionalDouble.empty(), 1E-6
        );
        assertEquals(first, result.targetUuid());
    }

    @Test void blockBeforeEntityBlocksHit() {
        HitResult result = new HitscanBallisticsService().trace(
            new Ray(new Vector3(0, 0, 0), new Vector3(1, 0, 0)),
            List.of(candidate(UUID.randomUUID(), 5)), 100,
            OptionalDouble.of(4), 1E-6
        );
        assertFalse(result.hit());
    }

    @Test void entityBeforeBlockHits() {
        HitResult result = new HitscanBallisticsService().trace(
            new Ray(new Vector3(0, 0, 0), new Vector3(1, 0, 0)),
            List.of(candidate(UUID.randomUUID(), 5)), 100,
            OptionalDouble.of(7), 1E-6
        );
        assertTrue(result.hit());
    }

    @Test void maximumRangeIsEnforced() {
        HitResult result = new HitscanBallisticsService().trace(
            new Ray(new Vector3(0, 0, 0), new Vector3(1, 0, 0)),
            List.of(candidate(UUID.randomUUID(), 5)), 4,
            OptionalDouble.empty(), 1E-6
        );
        assertFalse(result.hit());
    }

    @Test void headTakesPriorityAtSameDistance() {
        UUID id = UUID.randomUUID();
        HitCandidate candidate = new HitCandidate(
            id, UUID.randomUUID(), "world",
            new AxisAlignedBox(5, -1, -1, 6, 1, 1),
            new AxisAlignedBox(5, -1, -1, 6, 1, 1)
        );
        HitResult result = new HitscanBallisticsService().trace(
            new Ray(new Vector3(0, 0, 0), new Vector3(1, 0, 0)),
            List.of(candidate), 10, OptionalDouble.empty(), 1E-6
        );
        assertEquals(HitZone.HEAD, result.hitZone());
    }

    private static HitCandidate candidate(UUID id, double x) {
        UUID match = UUID.randomUUID();
        return new HitCandidate(
            id, match, "world",
            new AxisAlignedBox(x, -1, -1, x + 1, .5, 1),
            new AxisAlignedBox(x, .5, -1, x + 1, 1, 1)
        );
    }
}
