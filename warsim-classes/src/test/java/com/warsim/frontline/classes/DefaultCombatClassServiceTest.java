package com.warsim.frontline.classes;

import static org.junit.jupiter.api.Assertions.*;

import com.warsim.frontline.api.classes.*;
import com.warsim.frontline.api.roster.TeamSide;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultCombatClassServiceTest {
    private static final UUID MATCH = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-03T00:00:00Z");

    @Test void alivePlayerCannotStartDeploymentAgain() {
        DefaultCombatClassService service = service(configuration(2));
        UUID player = UUID.randomUUID();
        service.playerJoined(player, MATCH, Optional.of(CombatClassId.ASSAULT), NOW);
        service.markWaitingDeployment(player, MATCH, TeamSide.ATTACKERS, NOW);
        DeploymentContext initial = start(service, player, DeploymentReason.INITIAL_DEPLOYMENT, 0);
        assertTrue(service.markAlive(initial, NOW).successful());

        DeploymentResult duplicate = service.startDeployment(request(
            player, DeploymentReason.RESPAWN, TeamSide.ATTACKERS, 0
        ), 0, NOW);

        assertFalse(duplicate.successful());
        assertEquals(DeploymentFailureReason.INVALID_STATE, duplicate.failureReason());
        assertEquals(1, service.selection(player).orElseThrow().successfulDeploymentCount());
    }

    @Test void reasonMustMatchSuccessfulDeploymentCount() {
        DefaultCombatClassService service = service(configuration(2));
        UUID player = UUID.randomUUID();
        service.playerJoined(player, MATCH, Optional.of(CombatClassId.ASSAULT), NOW);
        service.markWaitingDeployment(player, MATCH, TeamSide.ATTACKERS, NOW);

        DeploymentResult result = service.startDeployment(request(
            player, DeploymentReason.RESPAWN, TeamSide.ATTACKERS, 0
        ), 0, NOW);

        assertFalse(result.successful());
        assertEquals(DeploymentFailureReason.INVALID_STATE, result.failureReason());
    }

    @Test void classLimitIsCountedPerTeamSide() {
        DefaultCombatClassService service = service(configuration(1));
        UUID attacker = UUID.randomUUID();
        UUID defender = UUID.randomUUID();
        service.playerJoined(attacker, MATCH, Optional.of(CombatClassId.ASSAULT), NOW);
        service.playerJoined(defender, MATCH, Optional.of(CombatClassId.ASSAULT), NOW);
        service.markWaitingDeployment(attacker, MATCH, TeamSide.ATTACKERS, NOW);
        service.markWaitingDeployment(defender, MATCH, TeamSide.DEFENDERS, NOW);

        assertTrue(service.startDeployment(request(
            attacker, DeploymentReason.INITIAL_DEPLOYMENT, TeamSide.ATTACKERS, 0
        ), 0, NOW).successful());
        assertTrue(service.startDeployment(request(
            defender, DeploymentReason.INITIAL_DEPLOYMENT, TeamSide.DEFENDERS, 0
        ), 0, NOW).successful());
    }

    @Test void waitingDeploymentStoresTeamSide() {
        DefaultCombatClassService service = service(configuration(2));
        UUID player = UUID.randomUUID();
        service.playerJoined(player, MATCH, Optional.of(CombatClassId.ASSAULT), NOW);

        DeploymentResult result = service.markWaitingDeployment(player, MATCH, TeamSide.DEFENDERS, NOW);

        assertTrue(result.successful());
        PlayerClassSelection selection = service.selection(player).orElseThrow();
        assertEquals(PlayerCombatState.WAITING_DEPLOYMENT, selection.combatState());
        assertEquals(Optional.of(TeamSide.DEFENDERS), selection.teamSide());
    }

    private static DeploymentContext start(DefaultCombatClassService service, UUID player,
                                           DeploymentReason reason, long nowNanos) {
        DeploymentResult result = service.startDeployment(
            request(player, reason, TeamSide.ATTACKERS, 0), nowNanos, NOW
        );
        assertTrue(result.successful());
        return result.context();
    }

    private static DeploymentRequest request(
        UUID player, DeploymentReason reason, TeamSide side, long delay
    ) {
        return new DeploymentRequest(
            player, MATCH, 1, CombatClassId.ASSAULT, side, reason,
            DeploymentTrigger.MANUAL, DeploymentSpawnType.TEAM_FIXED,
            Optional.of("team_fixed"), delay
        );
    }

    private static DefaultCombatClassService service(CombatClassConfiguration configuration) {
        DefaultCombatClassService service =
            new DefaultCombatClassService(MATCH, 1, configuration, ignored -> {});
        service.setDeploymentState(DeploymentSubsystemState.ACTIVE, null);
        return service;
    }

    private static CombatClassConfiguration configuration(int limit) {
        return new CombatClassConfiguration(true, 1, List.of(
            new CombatClassDefinition(
                CombatClassId.ASSAULT,
                "Assault",
                limit,
                new ClassEquipmentDefinition(Map.of())
            )
        ));
    }
}
