package com.warsim.frontline.api.loadtest;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identifier for a repeatable load scenario. */
public record LoadScenarioId(String value) implements Comparable<LoadScenarioId> {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,48}");

    public LoadScenarioId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid load scenario id");
        }
    }

    @Override public int compareTo(LoadScenarioId other) { return value.compareTo(other.value); }
    @Override public String toString() { return value; }
}
