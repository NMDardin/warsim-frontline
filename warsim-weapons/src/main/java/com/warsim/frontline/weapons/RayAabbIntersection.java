package com.warsim.frontline.weapons;

import com.warsim.frontline.api.weapon.AxisAlignedBox;
import com.warsim.frontline.api.weapon.Ray;
import java.util.OptionalDouble;

public final class RayAabbIntersection {
    private RayAabbIntersection() {}

    public static OptionalDouble intersect(Ray ray, AxisAlignedBox box, double epsilon) {
        double minimum = 0;
        double maximum = Double.POSITIVE_INFINITY;
        double[] origins = {ray.origin().x(), ray.origin().y(), ray.origin().z()};
        double[] directions = {ray.direction().x(), ray.direction().y(), ray.direction().z()};
        double[] lower = {box.minimumX(), box.minimumY(), box.minimumZ()};
        double[] upper = {box.maximumX(), box.maximumY(), box.maximumZ()};
        for (int index = 0; index < 3; index++) {
            double direction = directions[index];
            if (Math.abs(direction) <= epsilon) {
                if (origins[index] < lower[index] - epsilon
                    || origins[index] > upper[index] + epsilon) {
                    return OptionalDouble.empty();
                }
                continue;
            }
            double first = (lower[index] - origins[index]) / direction;
            double second = (upper[index] - origins[index]) / direction;
            if (first > second) {
                double temporary = first;
                first = second;
                second = temporary;
            }
            minimum = Math.max(minimum, first);
            maximum = Math.min(maximum, second);
            if (maximum + epsilon < minimum) {
                return OptionalDouble.empty();
            }
        }
        return maximum < -epsilon ? OptionalDouble.empty()
            : OptionalDouble.of(Math.max(0, minimum));
    }
}
