package com.warsim.frontline.vehicles;

public record VehicleHealthConfiguration(
    double maxHealth,
    double smallArmsMultiplier,
    double impactMultiplier,
    double environmentMultiplier,
    double adminMultiplier,
    double unknownMultiplier,
    boolean destroyAtZero,
    int despawnDelayTicks,
    boolean leaveWreck
) {
    public VehicleHealthConfiguration {
        finite(maxHealth, "maxHealth");
        if (maxHealth < 1.0 || maxHealth > 100_000.0) {
            throw new IllegalArgumentException("Vehicle max health must be 1-100000");
        }
        multiplier(smallArmsMultiplier, "smallArmsMultiplier");
        multiplier(impactMultiplier, "impactMultiplier");
        multiplier(environmentMultiplier, "environmentMultiplier");
        multiplier(adminMultiplier, "adminMultiplier");
        multiplier(unknownMultiplier, "unknownMultiplier");
        if (despawnDelayTicks < 0 || despawnDelayTicks > 1200) {
            throw new IllegalArgumentException("Vehicle despawn delay must be 0-1200 ticks");
        }
    }

    public static VehicleHealthConfiguration defaults() {
        return new VehicleHealthConfiguration(
            300.0, 0.35, 1.0, 0.5, 1.0, 0.25, true, 100, false
        );
    }

    public double multiplier(VehicleDamageType type) {
        return switch (type == null ? VehicleDamageType.UNKNOWN : type) {
            case ADMIN, SCRIPTED -> adminMultiplier;
            case SMALL_ARMS -> smallArmsMultiplier;
            case IMPACT -> impactMultiplier;
            case ENVIRONMENT -> environmentMultiplier;
            case UNKNOWN -> unknownMultiplier;
        };
    }

    private static void multiplier(double value, String name) {
        finite(value, name);
        if (value < 0.0 || value > 10.0) {
            throw new IllegalArgumentException(name + " must be 0-10");
        }
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
