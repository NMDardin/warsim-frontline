package com.warsim.frontline.destruction;

import java.util.Objects;

public record DestructionProtectedRegion(
    String id,
    String worldName,
    double minX,
    double minY,
    double minZ,
    double maxX,
    double maxY,
    double maxZ
) {
    public DestructionProtectedRegion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(worldName, "worldName");
        if (id.isBlank()) {
            throw new IllegalArgumentException("protected-region id must not be blank");
        }
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("protected-region world must not be blank");
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
            || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)) {
            throw new IllegalArgumentException("protected-region coordinates must be finite");
        }
        double lowX = Math.min(minX, maxX);
        double lowY = Math.min(minY, maxY);
        double lowZ = Math.min(minZ, maxZ);
        double highX = Math.max(minX, maxX);
        double highY = Math.max(minY, maxY);
        double highZ = Math.max(minZ, maxZ);
        minX = lowX;
        minY = lowY;
        minZ = lowZ;
        maxX = highX;
        maxY = highY;
        maxZ = highZ;
    }

    public boolean contains(String world, double x, double y, double z) {
        return worldName.equals(world)
            && x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }
}
