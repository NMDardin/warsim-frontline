package com.warsim.frontline.api.loadtest;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identifier for a load-test map definition. */
public record LoadMapId(String value) implements Comparable<LoadMapId> {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,32}");

    public LoadMapId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid load map id");
        }
    }

    @Override public int compareTo(LoadMapId other) { return value.compareTo(other.value); }
    @Override public String toString() { return value; }
}
