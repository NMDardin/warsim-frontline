package com.warsim.frontline.api.objective;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identifier for a configured battlefield objective. */
public record ObjectiveId(String value) implements Comparable<ObjectiveId> {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,32}");

    public ObjectiveId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Objective ID must match [a-z0-9_-]{1,32}");
        }
    }

    @Override
    public int compareTo(ObjectiveId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
