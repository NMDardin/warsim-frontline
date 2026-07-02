package com.warsim.frontline.vehicles;

import java.util.Optional;
import java.util.UUID;

public interface VehicleSeat {
    String id();

    Optional<UUID> occupantUuid();
}
