package com.warsim.frontline.api.weapon;

public record Vector3(double x, double y, double z) {
    public Vector3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Vector components must be finite");
        }
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public Vector3 normalized() {
        double lengthSquared = lengthSquared();
        if (lengthSquared <= 1.0E-18) {
            throw new IllegalArgumentException("Direction must not be zero");
        }
        double inverse = 1.0 / Math.sqrt(lengthSquared);
        return new Vector3(x * inverse, y * inverse, z * inverse);
    }

    public Vector3 add(Vector3 other) {
        return new Vector3(x + other.x, y + other.y, z + other.z);
    }

    public Vector3 multiply(double value) {
        return new Vector3(x * value, y * value, z * value);
    }

    public double dot(Vector3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vector3 cross(Vector3 other) {
        return new Vector3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x
        );
    }
}
