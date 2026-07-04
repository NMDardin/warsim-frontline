package com.warsim.frontline.vehicles;

public record VehicleMovementConfiguration(
    double maximumSpeedBlocksPerSecond,
    double accelerationBlocksPerSecondSquared,
    double turnDegreesPerSecond,
    double dragPerSecond,
    double stepHeight,
    double slopeLimitDegrees
) {
    public VehicleMovementConfiguration {
        finite(maximumSpeedBlocksPerSecond, "maximumSpeedBlocksPerSecond");
        finite(accelerationBlocksPerSecondSquared, "accelerationBlocksPerSecondSquared");
        finite(turnDegreesPerSecond, "turnDegreesPerSecond");
        finite(dragPerSecond, "dragPerSecond");
        finite(stepHeight, "stepHeight");
        finite(slopeLimitDegrees, "slopeLimitDegrees");
        if (maximumSpeedBlocksPerSecond < 0 || maximumSpeedBlocksPerSecond > 20) {
            throw new IllegalArgumentException("maximum speed must be 0-20 blocks/second");
        }
        if (accelerationBlocksPerSecondSquared < 0 || accelerationBlocksPerSecondSquared > 20) {
            throw new IllegalArgumentException("acceleration must be 0-20 blocks/second^2");
        }
        if (turnDegreesPerSecond < 0 || turnDegreesPerSecond > 360) {
            throw new IllegalArgumentException("turn speed must be 0-360 degrees/second");
        }
        if (dragPerSecond < 0 || dragPerSecond > 20) {
            throw new IllegalArgumentException("drag must be 0-20");
        }
        if (stepHeight < 0 || stepHeight > 4) {
            throw new IllegalArgumentException("step height must be 0-4");
        }
        if (slopeLimitDegrees < 0 || slopeLimitDegrees > 89) {
            throw new IllegalArgumentException("slope limit must be 0-89 degrees");
        }
    }

    public static VehicleMovementConfiguration defaults() {
        return new VehicleMovementConfiguration(4.0, 2.0, 90.0, 2.0, 1.0, 35.0);
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
