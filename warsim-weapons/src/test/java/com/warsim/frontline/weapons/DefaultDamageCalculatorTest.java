package com.warsim.frontline.weapons;

import static org.junit.jupiter.api.Assertions.*;
import com.warsim.frontline.api.roster.CombatRelation;
import com.warsim.frontline.api.weapon.*;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class DefaultDamageCalculatorTest {
    private final DefaultDamageCalculator calculator = new DefaultDamageCalculator();

    @ParameterizedTest
    @CsvSource({
        "0,34", "40,34", "80,34", "100,27", "120,20", "160,10", "200,10"
    })
    void interpolatesDamage(double distance, double expected) {
        assertEquals(expected, DefaultDamageCalculator.interpolate(
            List.of(
                new RangeDamagePoint(0, 34),
                new RangeDamagePoint(80, 34),
                new RangeDamagePoint(120, 20),
                new RangeDamagePoint(160, 10)
            ), distance
        ), 1E-6);
    }

    @ParameterizedTest
    @CsvSource({
        "SELF,false,false",
        "SQUADMATE,false,false",
        "TEAMMATE,false,false",
        "ENEMY,false,true",
        "UNKNOWN,false,false",
        "SELF,true,true",
        "SQUADMATE,true,true",
        "TEAMMATE,true,true"
    })
    void relationPolicy(CombatRelation relation, boolean enabled, boolean expected) {
        DamageResult result = calculator.calculate(new DamageRequest(
            definition(), 0, HitZone.BODY, relation, enabled, enabled
        ));
        assertEquals(expected, result.allowed());
    }

    @Test void headMultiplierAppliesAfterInterpolation() {
        DamageResult result = calculator.calculate(new DamageRequest(
            definition(), 50, HitZone.HEAD, CombatRelation.ENEMY, false, false
        ));
        assertEquals(30, result.damage(), 1E-6);
    }

    private static WeaponDefinition definition() {
        return new WeaponDefinition(
            new WeaponId("test"), "Test", WeaponCategory.RIFLE, FireMode.SEMI_AUTO,
            "warsim:test", new AmmoConfiguration(5, 10, 100), 60, 100,
            new AccuracyConfiguration(0),
            new DamageConfiguration(2, List.of(
                new RangeDamagePoint(0, 20), new RangeDamagePoint(100, 10)
            ))
        );
    }
}
