package com.warsim.frontline.api.weapon;

import java.util.Objects;
import java.util.UUID;

public record ShotId(UUID value) {
    public ShotId {
        Objects.requireNonNull(value, "value");
    }

    public static ShotId random() {
        return new ShotId(UUID.randomUUID());
    }
}
