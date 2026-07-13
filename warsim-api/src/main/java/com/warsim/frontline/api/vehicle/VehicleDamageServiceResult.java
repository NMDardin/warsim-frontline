package com.warsim.frontline.api.vehicle;

import java.util.Objects;
import java.util.UUID;

public record VehicleDamageServiceResult(
    boolean accepted,
    UUID runtimeId,
    double previousHealth,
    double newHealth,
    double damageApplied,
    boolean destroyed,
    VehicleDamageServiceOutcome outcome,
    String message
) {
    public VehicleDamageServiceResult {
        Objects.requireNonNull(outcome, "outcome");
        message = message == null ? "" : message;
    }
}
