package com.warsim.frontline.api.roster;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum SquadId {
    ALPHA("阿尔法"), BRAVO("布拉沃"), CHARLIE("查理"), DELTA("德尔塔"),
    ECHO("回声"), FOXTROT("狐步"), GOLF("高尔夫"), HOTEL("旅馆"),
    INDIA("印度"), JULIET("朱丽叶");

    private static final Map<String, SquadId> ALIASES = Map.ofEntries(
        Map.entry("阿尔法", ALPHA), Map.entry("布拉沃", BRAVO),
        Map.entry("查理", CHARLIE), Map.entry("德尔塔", DELTA),
        Map.entry("回声", ECHO), Map.entry("狐步", FOXTROT),
        Map.entry("高尔夫", GOLF), Map.entry("旅馆", HOTEL),
        Map.entry("印度", INDIA), Map.entry("朱丽叶", JULIET)
    );

    private final String displayName;

    SquadId(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<SquadId> parse(String value) {
        if (value == null) return Optional.empty();
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        try {
            return Optional.of(valueOf(normalized.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.ofNullable(ALIASES.get(value.trim()));
        }
    }
}
