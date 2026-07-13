package com.warsim.frontline.api.vehicle;

import com.warsim.frontline.api.weapon.HitCandidate;
import com.warsim.frontline.api.weapon.Vector3;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleDamageService {
    boolean isManagedVehicleAnchor(UUID anchorEntityUuid);

    Optional<UUID> runtimeIdForAnchor(UUID anchorEntityUuid);

    List<HitCandidate> hitCandidates(
        UUID matchId,
        String worldName,
        Vector3 origin,
        double maximumRange,
        int limit
    );

    VehicleDamageServiceResult damageVehicle(
        UUID runtimeId,
        double amount,
        VehicleDamageKind kind,
        Optional<UUID> attackerUuid,
        Optional<String> weaponId,
        String sourceDescription,
        Instant occurredAt
    );

    VehicleDamageServiceResult damageVehicleAnchor(
        UUID anchorEntityUuid,
        double amount,
        VehicleDamageKind kind,
        Optional<UUID> attackerUuid,
        Optional<String> weaponId,
        String sourceDescription,
        Instant occurredAt
    );
}
