package com.warsim.frontline.api.match;

public record MatchOperationResult(boolean accepted, String message) {
    public static MatchOperationResult success(String message) {
        return new MatchOperationResult(true, message);
    }

    public static MatchOperationResult rejected(String message) {
        return new MatchOperationResult(false, message);
    }
}
