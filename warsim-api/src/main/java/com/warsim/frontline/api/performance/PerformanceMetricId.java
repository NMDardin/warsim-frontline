package com.warsim.frontline.api.performance;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable metric identifier. */
public record PerformanceMetricId(String value) implements Comparable<PerformanceMetricId> {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_.-]{1,64}");

    public PerformanceMetricId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid performance metric id");
        }
    }

    @Override
    public int compareTo(PerformanceMetricId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
