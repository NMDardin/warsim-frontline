package com.warsim.frontline.weapons;

import com.warsim.frontline.api.roster.CombatRelation;
import com.warsim.frontline.api.weapon.*;
import java.util.List;

public final class DefaultDamageCalculator implements DamageService {
    @Override
    public DamageResult calculate(DamageRequest request) {
        boolean allowed = switch (request.relation()) {
            case ENEMY -> true;
            case SELF -> request.allowSelfDamage();
            case SQUADMATE, TEAMMATE -> request.friendlyFire();
            case UNKNOWN -> false;
        };
        if (!allowed) {
            return new DamageResult(false, 0, ShotOutcome.FRIENDLY_BLOCKED);
        }
        double damage = interpolate(request.definition().damage().points(), request.distance());
        if (request.hitZone() == HitZone.HEAD) {
            damage *= request.definition().damage().headMultiplier();
        }
        damage = Math.max(0, Math.min(1000, damage));
        return new DamageResult(
            true, damage,
            request.hitZone() == HitZone.HEAD
                ? ShotOutcome.FIRED_HEAD_HIT : ShotOutcome.FIRED_BODY_HIT
        );
    }

    public static double interpolate(List<RangeDamagePoint> points, double distance) {
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("Invalid distance");
        }
        if (distance <= points.getFirst().distance()) return points.getFirst().damage();
        for (int index = 1; index < points.size(); index++) {
            RangeDamagePoint right = points.get(index);
            RangeDamagePoint left = points.get(index - 1);
            if (distance <= right.distance()) {
                double ratio = (distance - left.distance())
                    / (right.distance() - left.distance());
                return left.damage() + (right.damage() - left.damage()) * ratio;
            }
        }
        return points.getLast().damage();
    }
}
