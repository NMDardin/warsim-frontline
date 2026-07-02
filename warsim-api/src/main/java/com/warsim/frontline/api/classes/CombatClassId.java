package com.warsim.frontline.api.classes;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Extensible combat class identifier. The built-in four classes are constants,
 * but deployments may add more classes through configuration without changing
 * the public API.
 */
public record CombatClassId(String value) implements Comparable<CombatClassId> {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,32}");

    public static final CombatClassId ASSAULT = new CombatClassId("assault");
    public static final CombatClassId MEDIC = new CombatClassId("medic");
    public static final CombatClassId SUPPORT = new CombatClassId("support");
    public static final CombatClassId SCOUT = new CombatClassId("scout");

    public CombatClassId {
        Objects.requireNonNull(value, "value");
        value = value.toLowerCase(Locale.ROOT);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("combat class id must match [a-z0-9_-]{1,32}");
        }
    }

    @Override
    public int compareTo(CombatClassId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
