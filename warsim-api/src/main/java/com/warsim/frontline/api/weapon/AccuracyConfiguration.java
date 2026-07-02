package com.warsim.frontline.api.weapon;

public record AccuracyConfiguration(double hipSpreadDegrees) {
    public AccuracyConfiguration {
        if (!Double.isFinite(hipSpreadDegrees)
            || hipSpreadDegrees < 0 || hipSpreadDegrees > 15) {
            throw new IllegalArgumentException("Invalid spread");
        }
    }
}
