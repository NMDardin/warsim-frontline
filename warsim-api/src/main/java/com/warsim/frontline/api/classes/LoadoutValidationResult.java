package com.warsim.frontline.api.classes;

public record LoadoutValidationResult(boolean valid, String message) {
    public static LoadoutValidationResult ok() {
        return new LoadoutValidationResult(true, "OK");
    }

    public static LoadoutValidationResult rejected(String message) {
        return new LoadoutValidationResult(false, message);
    }
}
