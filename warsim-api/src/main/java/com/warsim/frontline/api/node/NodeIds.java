package com.warsim.frontline.api.node;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validation utilities for internal node identifiers.
 */
public final class NodeIds {
    public static final int MAX_LENGTH = 48;
    private static final Pattern VALID = Pattern.compile("[a-z0-9-]{1,48}");

    private NodeIds() {
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    public static String requireValid(String value) {
        Objects.requireNonNull(value, "nodeId");
        if (!isValid(value)) {
            throw new IllegalArgumentException(
                "Node ID must contain only lowercase letters, digits, or hyphens and be 1-48 characters"
            );
        }
        return value;
    }
}
