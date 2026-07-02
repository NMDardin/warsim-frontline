package com.warsim.frontline.api.classes;

import java.util.Objects;

public record DeploymentResult(
    boolean successful,
    DeploymentFailureReason failureReason,
    DeploymentTransactionStage stage,
    String message,
    DeploymentContext context
) {
    public DeploymentResult {
        Objects.requireNonNull(failureReason, "failureReason");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(message, "message");
    }

    public static DeploymentResult rejected(
        DeploymentFailureReason reason, String message, DeploymentContext context
    ) {
        return new DeploymentResult(false, reason,
            context == null ? DeploymentTransactionStage.FAILED : context.stage(),
            message, context);
    }
}
