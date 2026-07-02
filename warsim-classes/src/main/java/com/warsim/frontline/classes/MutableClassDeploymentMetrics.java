package com.warsim.frontline.classes;

import com.warsim.frontline.api.classes.ClassDeploymentMetricsSnapshot;
import java.util.concurrent.atomic.AtomicLong;

public final class MutableClassDeploymentMetrics {
    public final AtomicLong initialDeployments = new AtomicLong();
    public final AtomicLong respawnDeployments = new AtomicLong();
    public final AtomicLong managedDropsRemoved = new AtomicLong();
    public final AtomicLong weaponStatesReset = new AtomicLong();
    public final AtomicLong loadoutTokensExpired = new AtomicLong();
    public final AtomicLong loadoutTokenReplayRejections = new AtomicLong();
    public final AtomicLong providerRegistrations = new AtomicLong();
    public final AtomicLong providerUnregistrations = new AtomicLong();
    public final AtomicLong disconnectRetentionRestores = new AtomicLong();
    public final AtomicLong waitingSpawnFallbacks = new AtomicLong();
    public final AtomicLong classLimitRejections = new AtomicLong();
    public final AtomicLong pendingClassApplications = new AtomicLong();
    public final AtomicLong pendingClassApplicationFailures = new AtomicLong();
    public final AtomicLong loadoutPreparations = new AtomicLong();
    public final AtomicLong loadoutPreparationFailures = new AtomicLong();
    public final AtomicLong deploymentRollbacks = new AtomicLong();
    public final AtomicLong ticketRefunds = new AtomicLong();
    public final AtomicLong ticketRefundFailures = new AtomicLong();
    public final AtomicLong staleDeploymentsRejected = new AtomicLong();
    public final AtomicLong unsafeSpawnRejections = new AtomicLong();
    public final AtomicLong providerUnavailableRejections = new AtomicLong();

    ClassDeploymentMetricsSnapshot snapshot() {
        return new ClassDeploymentMetricsSnapshot(
            initialDeployments.get(),
            respawnDeployments.get(),
            managedDropsRemoved.get(),
            weaponStatesReset.get(),
            loadoutTokensExpired.get(),
            loadoutTokenReplayRejections.get(),
            providerRegistrations.get(),
            providerUnregistrations.get(),
            disconnectRetentionRestores.get(),
            waitingSpawnFallbacks.get(),
            classLimitRejections.get(),
            pendingClassApplications.get(),
            pendingClassApplicationFailures.get(),
            loadoutPreparations.get(),
            loadoutPreparationFailures.get(),
            deploymentRollbacks.get(),
            ticketRefunds.get(),
            ticketRefundFailures.get(),
            staleDeploymentsRejected.get(),
            unsafeSpawnRejections.get(),
            providerUnavailableRejections.get()
        );
    }
}
