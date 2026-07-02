package com.warsim.frontline.vehicles;

import java.util.List;
import java.util.UUID;

public interface VehicleInstance {
    UUID id();

    VehicleType type();

    List<VehicleSeat> seats();
}
