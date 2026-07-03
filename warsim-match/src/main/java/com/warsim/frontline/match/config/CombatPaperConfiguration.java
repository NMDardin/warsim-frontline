package com.warsim.frontline.match.config;

public record CombatPaperConfiguration(
    boolean combatEnabled,
    boolean hudEnabled,
    boolean killFeedEnabled,
    boolean feedbackEnabled,
    int maximumContributorsPerTarget,
    double assistMinimumDamage,
    double assistMinimumPercentage,
    long attributionTtlNanos,
    long damageCorrelationTtlNanos,
    boolean environmentalAttributionEnabled,
    long environmentalAttributionTtlNanos,
    boolean friendlyFireEnabled,
    boolean spawnProtectionEnabled,
    long spawnProtectionDurationNanos,
    boolean blockIncomingCombatDamage,
    boolean removeOnWeaponFire,
    boolean removeOnMeleeAttack,
    boolean removeOnObjectivePresence,
    boolean removeOnLeaveRadius,
    double protectedRadius,
    int hudUpdateIntervalTicks,
    int killFeedMaximumEntries,
    long killFeedTtlNanos,
    long killFeedThrottleNanos
) {
    public CombatPaperConfiguration {
        if (maximumContributorsPerTarget < 1 || maximumContributorsPerTarget > 32) {
            throw new IllegalArgumentException("maximum-contributors-per-target must be 1-32");
        }
        if (!Double.isFinite(assistMinimumDamage) || assistMinimumDamage < 0) {
            throw new IllegalArgumentException("assist minimum-damage must be non-negative");
        }
        if (!Double.isFinite(assistMinimumPercentage)
            || assistMinimumPercentage < 0 || assistMinimumPercentage > 1) {
            throw new IllegalArgumentException("assist minimum-percentage must be 0-1");
        }
        if (attributionTtlNanos <= 0 || damageCorrelationTtlNanos <= 0
            || environmentalAttributionTtlNanos <= 0 || spawnProtectionDurationNanos <= 0) {
            throw new IllegalArgumentException("combat timing values must be positive");
        }
        if (!Double.isFinite(protectedRadius) || protectedRadius < 0 || protectedRadius > 64) {
            throw new IllegalArgumentException("protected-radius must be 0-64");
        }
        if (hudUpdateIntervalTicks < 1 || hudUpdateIntervalTicks > 200) {
            throw new IllegalArgumentException("hud update interval must be 1-200 ticks");
        }
        if (killFeedMaximumEntries < 1 || killFeedMaximumEntries > 100) {
            throw new IllegalArgumentException("kill-feed maximum entries must be 1-100");
        }
        if (killFeedTtlNanos <= 0 || killFeedThrottleNanos < 0) {
            throw new IllegalArgumentException("kill-feed timing values are invalid");
        }
    }

    public static CombatPaperConfiguration disabled() {
        return defaults(false);
    }

    public static CombatPaperConfiguration defaults(boolean officialBattle) {
        return new CombatPaperConfiguration(
            officialBattle, officialBattle, officialBattle, officialBattle,
            8, 20.0, 0.20, seconds(10), seconds(2),
            false, seconds(3),
            false,
            officialBattle, seconds(5), true, true, true, true, true,
            5.0, 10, 20, seconds(8), seconds(1)
        );
    }

    public static long seconds(long seconds) {
        return seconds * 1_000_000_000L;
    }
}
