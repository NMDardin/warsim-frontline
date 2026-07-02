package com.warsim.frontline.weapons;

import com.warsim.frontline.api.weapon.*;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

public final class HitscanBallisticsService implements BallisticsService {
    @Override
    public HitResult trace(
        Ray ray, List<HitCandidate> candidates, double maximumRange,
        OptionalDouble blockHitDistance, double epsilon
    ) {
        HitResult best = HitResult.miss();
        for (HitCandidate candidate : candidates) {
            HitResult candidateHit = hitCandidate(ray, candidate, epsilon);
            if (!candidateHit.hit() || candidateHit.distance() > maximumRange + epsilon) continue;
            if (blockHitDistance.isPresent()
                && candidateHit.distance() > blockHitDistance.getAsDouble() + epsilon) continue;
            if (!best.hit() || candidateHit.distance() < best.distance() - epsilon
                || Math.abs(candidateHit.distance() - best.distance()) <= epsilon
                    && candidateHit.targetUuid().toString()
                        .compareTo(best.targetUuid().toString()) < 0) {
                best = candidateHit;
            }
        }
        return best;
    }

    private static HitResult hitCandidate(Ray ray, HitCandidate candidate, double epsilon) {
        OptionalDouble head = RayAabbIntersection.intersect(ray, candidate.headBox(), epsilon);
        OptionalDouble body = RayAabbIntersection.intersect(ray, candidate.bodyBox(), epsilon);
        if (head.isEmpty() && body.isEmpty()) return HitResult.miss();
        if (head.isPresent() && (body.isEmpty()
            || head.getAsDouble() <= body.getAsDouble() + epsilon)) {
            return new HitResult(candidate.targetUuid(), HitZone.HEAD, head.getAsDouble());
        }
        return new HitResult(candidate.targetUuid(), HitZone.BODY, body.getAsDouble());
    }
}
