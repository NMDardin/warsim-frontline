package com.warsim.frontline.vehicles;

import java.util.Objects;

public record VehicleDamageResult(
    boolean accepted,
    VehicleRuntimeId runtimeId,
    double previousHealth,
    double newHealth,
    double damageApplied,
    boolean destroyed,
    VehicleDamageOutcome outcome,
    String message
) {
    public VehicleDamageResult {
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(outcome, "outcome");
        message = message == null ? "" : message;
    }
}
