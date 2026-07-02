package com.warsim.frontline.vehicles;

import java.util.Objects;
import java.util.regex.Pattern;

public record VehicleType(String id, String displayName, int maximumSeats) {
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,48}");

    public VehicleType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid vehicle type ID");
        }
        if (displayName.isBlank() || displayName.length() > 64) {
            throw new IllegalArgumentException("Invalid vehicle display name");
        }
        if (maximumSeats < 1 || maximumSeats > 16) {
            throw new IllegalArgumentException("maximumSeats must be 1-16");
        }
    }
}
