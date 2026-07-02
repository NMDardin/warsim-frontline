package com.warsim.frontline.api.weapon;

public interface BallisticsService {
    HitResult trace(
        Ray ray, java.util.List<HitCandidate> candidates, double maximumRange,
        java.util.OptionalDouble blockHitDistance, double epsilon
    );
}
