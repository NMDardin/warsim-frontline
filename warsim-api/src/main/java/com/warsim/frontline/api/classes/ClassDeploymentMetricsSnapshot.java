package com.warsim.frontline.api.classes;

public record ClassDeploymentMetricsSnapshot(
    long initialDeployments,
    long respawnDeployments,
    long managedDropsRemoved,
    long weaponStatesReset,
    long loadoutTokensExpired,
    long loadoutTokenReplayRejections,
    long providerRegistrations,
    long providerUnregistrations,
    long disconnectRetentionRestores,
    long waitingSpawnFallbacks,
    long classLimitRejections,
    long pendingClassApplications,
    long pendingClassApplicationFailures,
    long loadoutPreparations,
    long loadoutPreparationFailures,
    long deploymentRollbacks,
    long ticketRefunds,
    long ticketRefundFailures,
    long staleDeploymentsRejected,
    long unsafeSpawnRejections,
    long providerUnavailableRejections
) {
    public static ClassDeploymentMetricsSnapshot empty() {
        return new ClassDeploymentMetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
