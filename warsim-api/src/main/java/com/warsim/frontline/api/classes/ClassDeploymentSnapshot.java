package com.warsim.frontline.api.classes;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ClassDeploymentSnapshot(
    ClassSubsystemState classState,
    DeploymentSubsystemState deploymentState,
    UUID matchId,
    long lifecycleRevision,
    long classConfigurationRevision,
    List<PlayerClassSelection> selections,
    ClassDeploymentMetricsSnapshot metrics,
    Optional<String> lastError
) {
    public ClassDeploymentSnapshot {
        Objects.requireNonNull(classState, "classState");
        Objects.requireNonNull(deploymentState, "deploymentState");
        Objects.requireNonNull(matchId, "matchId");
        selections = selections == null ? List.of() : List.copyOf(selections);
        Objects.requireNonNull(metrics, "metrics");
        lastError = lastError == null ? Optional.empty() : lastError;
    }
}
