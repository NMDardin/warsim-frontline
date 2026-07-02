package com.warsim.frontline.vehicles;

import java.util.Optional;
import java.util.UUID;

/**
 * Vehicle business logic only. Rendering is owned by ModelEngine and base entities/skills by MythicMobs.
 */
public interface VehicleService {
    Optional<VehicleInstance> find(UUID vehicleId);
}
