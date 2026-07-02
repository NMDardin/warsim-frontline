package com.warsim.frontline.api.match;

public record MatchResetResult(boolean successful, String summary) {
    public static MatchResetResult success() {
        return new MatchResetResult(true, "重置完成");
    }

    public static MatchResetResult failure(String summary) {
        return new MatchResetResult(false, summary);
    }
}
