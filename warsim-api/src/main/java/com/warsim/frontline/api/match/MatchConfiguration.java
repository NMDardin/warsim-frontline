package com.warsim.frontline.api.match;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record MatchConfiguration(
    boolean enabled,
    String modeId,
    int minimumPlayers,
    int maximumPlayers,
    boolean cancelWarmupBelowMinimum,
    boolean allowMidRoundJoin,
    int warmupSeconds,
    int roundDurationSeconds,
    int endingSeconds,
    int resetTimeoutSeconds,
    boolean autoStart,
    boolean autoCycle,
    boolean announcementsEnabled,
    List<Integer> warmupAnnouncements
) {
    public static final String OFFENSIVE_MODE = "frontline_offensive";

    public MatchConfiguration {
        Objects.requireNonNull(modeId, "modeId");
        Objects.requireNonNull(warmupAnnouncements, "warmupAnnouncements");
        if (!OFFENSIVE_MODE.equals(modeId)) {
            throw new IllegalArgumentException("Unsupported match mode");
        }
        if (minimumPlayers < 1 || minimumPlayers > 100) {
            throw new IllegalArgumentException("minimumPlayers must be 1-100");
        }
        if (maximumPlayers < 2 || maximumPlayers > 100 || minimumPlayers > maximumPlayers) {
            throw new IllegalArgumentException("Invalid maximumPlayers");
        }
        if (warmupSeconds < 5 || warmupSeconds > 600) {
            throw new IllegalArgumentException("warmupSeconds must be 5-600");
        }
        if (roundDurationSeconds < 60 || roundDurationSeconds > 7200) {
            throw new IllegalArgumentException("roundDurationSeconds must be 60-7200");
        }
        if (endingSeconds < 3 || endingSeconds > 120) {
            throw new IllegalArgumentException("endingSeconds must be 3-120");
        }
        if (resetTimeoutSeconds < 5 || resetTimeoutSeconds > 300) {
            throw new IllegalArgumentException("resetTimeoutSeconds must be 5-300");
        }
        if (warmupAnnouncements.stream().anyMatch(value ->
            value == null || value <= 0 || value > warmupSeconds)) {
            throw new IllegalArgumentException("Invalid warmup announcement");
        }
        warmupAnnouncements = warmupAnnouncements.stream()
            .distinct()
            .sorted(Comparator.reverseOrder())
            .toList();
    }

    public static MatchConfiguration defaults(boolean enabled) {
        return new MatchConfiguration(
            enabled, OFFENSIVE_MODE, 40, 100, true, true,
            60, 2700, 15, 30, true, true, true,
            List.of(60, 30, 10, 5, 4, 3, 2, 1)
        );
    }
}
