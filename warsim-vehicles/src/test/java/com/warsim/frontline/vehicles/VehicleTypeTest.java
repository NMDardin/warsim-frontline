package com.warsim.frontline.vehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VehicleTypeTest {
    @Test
    void validatesDefinition() {
        VehicleType tank = new VehicleType("mark-v", "Mark V", 8);
        assertEquals(8, tank.maximumSeats());
        assertThrows(IllegalArgumentException.class, () -> new VehicleType("Mark V", "Mark V", 8));
        assertThrows(IllegalArgumentException.class, () -> new VehicleType("mark-v", "Mark V", 0));
    }
}
