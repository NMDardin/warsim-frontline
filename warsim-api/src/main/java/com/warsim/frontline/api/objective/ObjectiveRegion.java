package com.warsim.frontline.api.objective;

import java.util.Objects;

/** Platform-independent cylindrical objective region. */
public record ObjectiveRegion(
    String worldName,
    double centerX,
    double centerY,
    double centerZ,
    double radius,
    double verticalRange
) {
    public ObjectiveRegion {
        Objects.requireNonNull(worldName, "worldName");
        if (worldName.isBlank()) throw new IllegalArgumentException("worldName is blank");
        if (!finite(centerX, centerY, centerZ, radius, verticalRange)) {
            throw new IllegalArgumentException("Objective region values must be finite");
        }
        if (radius < 1 || radius > 64) {
            throw new IllegalArgumentException("radius must be 1-64");
        }
        if (verticalRange < 1 || verticalRange > 64) {
            throw new IllegalArgumentException("verticalRange must be 1-64");
        }
    }

    public boolean contains(String world, double x, double y, double z) {
        if (!worldName.equals(world)) return false;
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz <= radius * radius
            && Math.abs(y - centerY) <= verticalRange;
    }

    public double horizontalDistanceSquared(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
