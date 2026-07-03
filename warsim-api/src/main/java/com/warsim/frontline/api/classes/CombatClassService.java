package com.warsim.frontline.api.classes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.warsim.frontline.api.roster.TeamSide;

public interface CombatClassService extends CombatEligibilityService, AutoCloseable {
    ClassDeploymentSnapshot snapshot();

    List<CombatClassDefinition> definitions();

    Optional<PlayerClassSelection> selection(UUID playerUuid);

    DeploymentResult selectClass(UUID playerUuid, UUID matchId, CombatClassId classId, Instant now);

    DeploymentResult clearClass(UUID playerUuid, UUID matchId, Instant now);

    DeploymentResult startDeployment(DeploymentRequest request, long nowMonotonic, Instant now);

    Optional<DeploymentContext> activeDeployment(UUID playerUuid);

    DeploymentResult cancelDeployment(UUID playerUuid, String reason, Instant now);

    DeploymentResult markAlive(DeploymentContext context, Instant now);

    DeploymentResult revalidateDeploymentCapacity(DeploymentContext context, Instant now);

    DeploymentResult markDead(UUID playerUuid, UUID matchId, long lifeRevision, Instant now);

    DeploymentResult markWaitingDeployment(
        UUID playerUuid,
        UUID matchId,
        TeamSide teamSide,
        Instant now
    );

    void playerJoined(UUID playerUuid, UUID matchId, Optional<CombatClassId> preferred, Instant now);

    void playerDisconnected(UUID playerUuid, Instant now);

    void closePlayer(UUID playerUuid, Instant now);

    AutoCloseable subscribe(ClassDeploymentEventListener listener);
}
