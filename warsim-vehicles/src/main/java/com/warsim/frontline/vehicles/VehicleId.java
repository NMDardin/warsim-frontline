package com.warsim.frontline.vehicles;

import java.util.Objects;
import java.util.regex.Pattern;

public record VehicleId(String value) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");

    public VehicleId {
        Objects.requireNonNull(value, "value");
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid vehicle id");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
