package com.warsim.frontline.api.objective;

public record ObjectivePresence(int attackersPresent, int defendersPresent) {
    public ObjectivePresence {
        if (attackersPresent < 0 || defendersPresent < 0) {
            throw new IllegalArgumentException("Presence counts cannot be negative");
        }
    }
}
