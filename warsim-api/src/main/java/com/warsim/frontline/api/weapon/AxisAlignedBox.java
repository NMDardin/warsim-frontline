package com.warsim.frontline.api.weapon;

public record AxisAlignedBox(
    double minimumX, double minimumY, double minimumZ,
    double maximumX, double maximumY, double maximumZ
) {
    public AxisAlignedBox {
        if (!Double.isFinite(minimumX) || !Double.isFinite(minimumY)
            || !Double.isFinite(minimumZ) || !Double.isFinite(maximumX)
            || !Double.isFinite(maximumY) || !Double.isFinite(maximumZ)
            || minimumX > maximumX || minimumY > maximumY || minimumZ > maximumZ) {
            throw new IllegalArgumentException("Invalid axis-aligned box");
        }
    }
}
