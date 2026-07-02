package com.warsim.frontline.api.loadtest;

import java.util.Objects;
import java.util.regex.Pattern;

/** Human-readable semantic-ish map version. */
public record LoadMapVersion(String value) {
    private static final Pattern VALID = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");

    public LoadMapVersion {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid load map version");
        }
    }
}
