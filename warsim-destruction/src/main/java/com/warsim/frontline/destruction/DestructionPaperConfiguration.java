package com.warsim.frontline.destruction;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;

public record DestructionPaperConfiguration(
    boolean enabled,
    boolean recordEntityExplosions,
    boolean recordBlockExplosions,
    boolean recordPlayerBlockBreaks,
    boolean recordPlayerBlockPlaces,
    boolean restoreEnabled,
    int maximumBlocksPerMatch,
    int maximumBlocksPerReset,
    Set<String> worlds,
    Set<Material> allowList,
    Set<Material> denyList,
    List<DestructionProtectedRegion> protectedRegions
) {
    public DestructionPaperConfiguration {
        Objects.requireNonNull(worlds, "worlds");
        Objects.requireNonNull(allowList, "allowList");
        Objects.requireNonNull(denyList, "denyList");
        Objects.requireNonNull(protectedRegions, "protectedRegions");
        if (enabled && !restoreEnabled) {
            throw new IllegalArgumentException("destruction.restore.enabled must be true when destruction is enabled");
        }
        if (maximumBlocksPerMatch < 0 || maximumBlocksPerMatch > 200_000) {
            throw new IllegalArgumentException("maximum-blocks-per-match must be 0-200000");
        }
        if (maximumBlocksPerReset < 0 || maximumBlocksPerReset > 200_000) {
            throw new IllegalArgumentException("maximum-blocks-per-reset must be 0-200000");
        }
        for (String world : worlds) {
            if (world == null || world.isBlank() || world.contains("/") || world.contains("\\")) {
                throw new IllegalArgumentException("destruction.worlds contains an invalid world name");
            }
        }
        for (Material material : allowList) {
            if (material == null || !material.isBlock()) {
                throw new IllegalArgumentException("destruction.materials.allow-list must contain only block materials");
            }
        }
        for (Material material : denyList) {
            if (material == null || !material.isBlock()) {
                throw new IllegalArgumentException("destruction.materials.deny-list must contain only block materials");
            }
        }
        worlds = Set.copyOf(worlds);
        allowList = Set.copyOf(allowList);
        denyList = Set.copyOf(denyList);
        protectedRegions = List.copyOf(protectedRegions);
    }

    public static DestructionPaperConfiguration disabled() {
        return new DestructionPaperConfiguration(
            false, false, false, false, false, false,
            0, 0, Set.of(), Set.of(), Set.of(), List.of()
        );
    }
}
