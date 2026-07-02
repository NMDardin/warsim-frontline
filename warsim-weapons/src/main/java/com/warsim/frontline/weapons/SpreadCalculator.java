package com.warsim.frontline.weapons;

import com.warsim.frontline.api.weapon.RandomSource;
import com.warsim.frontline.api.weapon.Vector3;

public final class SpreadCalculator {
    public Vector3 apply(Vector3 direction, double degrees, RandomSource random) {
        Vector3 forward = direction.normalized();
        if (degrees == 0) return forward;
        double maximum = Math.toRadians(degrees);
        double cosTheta = 1 - random.nextDouble() * (1 - Math.cos(maximum));
        double sinTheta = Math.sqrt(Math.max(0, 1 - cosTheta * cosTheta));
        double phi = random.nextDouble() * Math.PI * 2;
        Vector3 helper = Math.abs(forward.y()) < .999
            ? new Vector3(0, 1, 0) : new Vector3(1, 0, 0);
        Vector3 right = helper.cross(forward).normalized();
        Vector3 up = forward.cross(right).normalized();
        return forward.multiply(cosTheta)
            .add(right.multiply(Math.cos(phi) * sinTheta))
            .add(up.multiply(Math.sin(phi) * sinTheta))
            .normalized();
    }
}
