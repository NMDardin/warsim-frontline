package com.warsim.frontline.destruction;

import java.util.Objects;

public record DestructionBlockKey(String worldName, int x, int y, int z) {
    public DestructionBlockKey {
        Objects.requireNonNull(worldName, "worldName");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
    }
}
