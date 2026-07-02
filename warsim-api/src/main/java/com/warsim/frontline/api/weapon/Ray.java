package com.warsim.frontline.api.weapon;

import java.util.Objects;

public record Ray(Vector3 origin, Vector3 direction) {
    public Ray {
        Objects.requireNonNull(origin, "origin");
        direction = Objects.requireNonNull(direction, "direction").normalized();
    }
}
