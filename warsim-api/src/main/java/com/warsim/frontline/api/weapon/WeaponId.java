package com.warsim.frontline.api.weapon;

import java.util.Objects;

public record WeaponId(String value) implements Comparable<WeaponId> {
    public WeaponId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid weapon ID");
        }
    }

    @Override public int compareTo(WeaponId other) {
        return value.compareTo(other.value);
    }

    @Override public String toString() {
        return value;
    }
}
