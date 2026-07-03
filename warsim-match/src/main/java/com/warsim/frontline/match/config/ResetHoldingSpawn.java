package com.warsim.frontline.match.config;

import java.util.Objects;

public record ResetHoldingSpawn(
    String world,
    double x,
    double y,
    double z,
    float yaw,
    float pitch
) {
    public ResetHoldingSpawn {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("reset holding-spawn world must be configured");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("reset holding-spawn coordinates must be finite");
        }
    }
}
