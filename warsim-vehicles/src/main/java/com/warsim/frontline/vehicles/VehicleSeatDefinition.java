package com.warsim.frontline.vehicles;

import java.util.Objects;
import java.util.regex.Pattern;

public record VehicleSeatDefinition(
    String id,
    String displayName,
    double xOffset,
    double yOffset,
    double zOffset,
    boolean driver
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    public VehicleSeatDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid vehicle seat id");
        }
        if (displayName.isBlank() || displayName.length() > 64) {
            throw new IllegalArgumentException("Invalid vehicle seat display name");
        }
        if (!Double.isFinite(xOffset) || !Double.isFinite(yOffset) || !Double.isFinite(zOffset)) {
            throw new IllegalArgumentException("Vehicle seat offsets must be finite");
        }
    }
}
