package com.warsim.frontline.api.loadtest;

/** Immutable world coordinate used by load-test templates. */
public record LoadCoordinate(String world, double x, double y, double z, float yaw, float pitch) {
    public LoadCoordinate {
        if (world == null || world.isBlank() || world.length() > 64
            || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Invalid load coordinate");
        }
    }
}
