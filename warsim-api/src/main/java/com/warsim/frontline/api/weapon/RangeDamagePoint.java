package com.warsim.frontline.api.weapon;

public record RangeDamagePoint(double distance, double damage) {
    public RangeDamagePoint {
        if (!Double.isFinite(distance) || distance < 0
            || !Double.isFinite(damage) || damage < 0 || damage > 1000) {
            throw new IllegalArgumentException("Invalid damage point");
        }
    }
}
