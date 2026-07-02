package com.warsim.frontline.api.objective;

public record ObjectiveOperationResult(
    boolean successful,
    String message,
    ObjectiveSnapshot snapshot
) {
    public static ObjectiveOperationResult rejected(String message) {
        return new ObjectiveOperationResult(false, message, null);
    }

    public static ObjectiveOperationResult success(String message, ObjectiveSnapshot snapshot) {
        return new ObjectiveOperationResult(true, message, snapshot);
    }
}
