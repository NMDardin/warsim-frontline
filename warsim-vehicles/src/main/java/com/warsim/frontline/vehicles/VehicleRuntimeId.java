package com.warsim.frontline.vehicles;

import java.util.Objects;
import java.util.UUID;

public record VehicleRuntimeId(UUID value) {
    public VehicleRuntimeId {
        Objects.requireNonNull(value, "value");
    }

    public static VehicleRuntimeId random() {
        return new VehicleRuntimeId(UUID.randomUUID());
    }

    public String shortText() {
        return value.toString().substring(0, 8);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
