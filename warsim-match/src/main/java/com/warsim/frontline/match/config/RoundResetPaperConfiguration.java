package com.warsim.frontline.match.config;

import java.util.Objects;
import java.util.Set;

public record RoundResetPaperConfiguration(
    boolean enabled,
    int startDelayTicks,
    boolean evacuateOnlinePlayers,
    ResetHoldingSpawn holdingSpawn,
    Set<String> transientWorlds,
    int maximumTransientEntities
) {
    public RoundResetPaperConfiguration {
        Objects.requireNonNull(transientWorlds, "transientWorlds");
        if (startDelayTicks < 1 || startDelayTicks > 20) {
            throw new IllegalArgumentException("match.reset.start-delay-ticks must be 1-20");
        }
        if (maximumTransientEntities < 0 || maximumTransientEntities > 100000) {
            throw new IllegalArgumentException("match.reset.maximum-transient-entities must be 0-100000");
        }
        transientWorlds = transientWorlds.stream()
            .map(world -> {
                if (world == null) {
                    throw new IllegalArgumentException("match.reset.transient-worlds contains null");
                }
                String normalized = world.strip();
                if (normalized.isBlank()) {
                    throw new IllegalArgumentException("match.reset.transient-worlds contains blank world name");
                }
                if (normalized.contains("/") || normalized.contains("\\")) {
                    throw new IllegalArgumentException("match.reset.transient-worlds must contain world names, not paths");
                }
                return normalized;
            })
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (enabled && evacuateOnlinePlayers && holdingSpawn == null) {
            throw new IllegalArgumentException("enabled reset evacuation requires holding-spawn");
        }
    }

    public static RoundResetPaperConfiguration disabled() {
        return new RoundResetPaperConfiguration(false, 1, false, null, Set.of(), 0);
    }
}
