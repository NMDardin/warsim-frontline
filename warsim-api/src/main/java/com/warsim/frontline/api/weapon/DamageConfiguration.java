package com.warsim.frontline.api.weapon;

import java.util.List;
import java.util.Objects;

public record DamageConfiguration(double headMultiplier, List<RangeDamagePoint> points) {
    public DamageConfiguration {
        if (!Double.isFinite(headMultiplier) || headMultiplier < 1 || headMultiplier > 5) {
            throw new IllegalArgumentException("Invalid head multiplier");
        }
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        if (points.size() < 2 || points.size() > 16 || points.getFirst().distance() != 0) {
            throw new IllegalArgumentException("Damage points must contain 2-16 entries starting at zero");
        }
        double previous = -1;
        for (RangeDamagePoint point : points) {
            if (point.distance() <= previous) {
                throw new IllegalArgumentException("Damage point distances must strictly increase");
            }
            previous = point.distance();
        }
    }
}
