package com.warsim.frontline.api.objective;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identifier for an ordered objective sector. */
public record ObjectiveSectorId(String value) implements Comparable<ObjectiveSectorId> {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,32}");

    public ObjectiveSectorId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Objective sector ID must match [a-z0-9_-]{1,32}");
        }
    }

    @Override
    public int compareTo(ObjectiveSectorId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
