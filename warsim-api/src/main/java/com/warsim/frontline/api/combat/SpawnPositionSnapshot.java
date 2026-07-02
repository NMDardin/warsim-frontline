package com.warsim.frontline.api.combat;

public record SpawnPositionSnapshot(String worldName, double x, double y, double z) {
    public SpawnPositionSnapshot {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName is required");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Coordinates must be finite");
        }
    }
}
