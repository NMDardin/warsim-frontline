package com.warsim.frontline.api.weapon;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WeaponModelTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "a", "test_rifle", "test-pistol", "w1", "abc_123", "x-y_z",
        "0", "a2345678901234567890123456789012"
    })
    void acceptsValidWeaponIds(String value) {
        assertEquals(value, new WeaponId(value).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "UPPER", "has space", "a/b", "a:b", "a.b", "é", "_bad$",
        "a23456789012345678901234567890123", "\n", "x;y", "中文"
    })
    void rejectsInvalidWeaponIds(String value) {
        assertThrows(IllegalArgumentException.class, () -> new WeaponId(value));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void vectorRejectsNonFiniteX(double value) {
        assertThrows(IllegalArgumentException.class, () -> new Vector3(value, 0, 0));
    }

    @Test void zeroVectorCannotNormalize() {
        assertThrows(IllegalArgumentException.class, () -> new Vector3(0, 0, 0).normalized());
    }

    @Test void vectorNormalizes() {
        assertEquals(1, new Vector3(3, 4, 0).normalized().lengthSquared(), 1.0E-9);
    }

    @Test void invalidBoxIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new AxisAlignedBox(1, 0, 0, 0, 1, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 201})
    void magazineBounds(int value) {
        assertThrows(IllegalArgumentException.class,
            () -> new AmmoConfiguration(value, 1, 100));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1001})
    void reserveBounds(int value) {
        assertThrows(IllegalArgumentException.class,
            () -> new AmmoConfiguration(1, value, 100));
    }

    @ParameterizedTest
    @ValueSource(longs = {99, 15001})
    void reloadBounds(long value) {
        assertThrows(IllegalArgumentException.class,
            () -> new AmmoConfiguration(1, 1, value));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 15.1, Double.NaN})
    void spreadBounds(double value) {
        assertThrows(IllegalArgumentException.class,
            () -> new AccuracyConfiguration(value));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.9, 5.1, Double.NaN})
    void headMultiplierBounds(double value) {
        assertThrows(IllegalArgumentException.class,
            () -> new DamageConfiguration(value, points()));
    }

    @Test void damagePointsMustStartAtZero() {
        assertThrows(IllegalArgumentException.class, () -> new DamageConfiguration(
            1, List.of(new RangeDamagePoint(1, 1), new RangeDamagePoint(2, 1))
        ));
    }

    @Test void damagePointsMustIncrease() {
        assertThrows(IllegalArgumentException.class, () -> new DamageConfiguration(
            1, List.of(new RangeDamagePoint(0, 1), new RangeDamagePoint(0, 1))
        ));
    }

    @Test void onlySemiAutomaticDefinitionsCanBeEnabled() {
        WeaponDefinition automatic = definition(FireMode.AUTOMATIC);
        assertThrows(IllegalArgumentException.class, () -> new WeaponConfiguration(
            true, false, false, 100, 200, 250, .25, 1E-6, 2,
            List.of(automatic)
        ));
    }

    @Test void duplicateDefinitionRejected() {
        WeaponDefinition definition = definition(FireMode.SEMI_AUTO);
        assertThrows(IllegalArgumentException.class, () -> new WeaponConfiguration(
            true, false, false, 100, 200, 250, .25, 1E-6, 2,
            List.of(definition, definition)
        ));
    }

    @Test void lastDamagePointMustCoverRange() {
        assertThrows(IllegalArgumentException.class, () -> new WeaponDefinition(
            new WeaponId("x"), "X", WeaponCategory.RIFLE, FireMode.SEMI_AUTO,
            "warsim:x", new AmmoConfiguration(1, 0, 100), 60, 20,
            new AccuracyConfiguration(0),
            new DamageConfiguration(1, List.of(
                new RangeDamagePoint(0, 1), new RangeDamagePoint(10, 1)
            ))
        ));
    }

    private static WeaponDefinition definition(FireMode mode) {
        return new WeaponDefinition(
            new WeaponId("test"), "Test", WeaponCategory.RIFLE, mode,
            "warsim:test", new AmmoConfiguration(5, 30, 1000), 60, 100,
            new AccuracyConfiguration(0), new DamageConfiguration(2, points())
        );
    }

    private static List<RangeDamagePoint> points() {
        return List.of(new RangeDamagePoint(0, 10), new RangeDamagePoint(100, 5));
    }
}
