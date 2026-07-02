package com.warsim.frontline.api.classes;

public record LoadoutProvisionResult(
    boolean successful,
    String message,
    LoadoutPreparationToken token
) {
    public static LoadoutProvisionResult success(String message, LoadoutPreparationToken token) {
        return new LoadoutProvisionResult(true, message, token);
    }

    public static LoadoutProvisionResult rejected(String message) {
        return new LoadoutProvisionResult(false, message, null);
    }
}
