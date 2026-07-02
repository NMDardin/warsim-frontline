package com.warsim.frontline.api.weapon;

public record WeaponOperationResult(
    boolean successful,
    WeaponFailureReason reason,
    String message,
    WeaponRuntimeState state
) {
    public static WeaponOperationResult rejected(WeaponFailureReason reason, String message) {
        return new WeaponOperationResult(false, reason, message, null);
    }
}
