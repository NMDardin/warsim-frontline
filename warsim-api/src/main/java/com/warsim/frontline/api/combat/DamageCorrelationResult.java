package com.warsim.frontline.api.combat;

public record DamageCorrelationResult(
    boolean successful,
    String message,
    DamageCorrelationToken token
) {
    public static DamageCorrelationResult rejected(String message) {
        return new DamageCorrelationResult(false, message, null);
    }
}
