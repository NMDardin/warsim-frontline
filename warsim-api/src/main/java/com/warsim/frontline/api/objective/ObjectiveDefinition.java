package com.warsim.frontline.api.objective;

import java.util.Objects;

public record ObjectiveDefinition(
    ObjectiveId objectiveId,
    String displayName,
    ObjectiveRegion region,
    ObjectiveOwner initialOwner,
    boolean initiallyLocked,
    ObjectiveCaptureRules captureRules,
    ObjectiveRewards rewards
) {
    public ObjectiveDefinition {
        Objects.requireNonNull(objectiveId, "objectiveId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(initialOwner, "initialOwner");
        Objects.requireNonNull(captureRules, "captureRules");
        Objects.requireNonNull(rewards, "rewards");
        if (displayName.isBlank() || displayName.length() > 32) {
            throw new IllegalArgumentException("displayName must be 1-32 characters");
        }
    }
}
