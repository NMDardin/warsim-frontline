package com.warsim.frontline.api.weapon;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

public record ShotContext(
    ShotRequest request,
    List<HitCandidate> candidates,
    OptionalDouble blockHitDistance
) {
    public ShotContext {
        Objects.requireNonNull(request, "request");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        blockHitDistance = blockHitDistance == null ? OptionalDouble.empty() : blockHitDistance;
        if (blockHitDistance.isPresent()
            && (!Double.isFinite(blockHitDistance.getAsDouble())
                || blockHitDistance.getAsDouble() < 0)) {
            throw new IllegalArgumentException("Invalid block hit distance");
        }
    }
}
