package com.warsim.frontline.match.paper;

import com.warsim.frontline.api.match.MatchState;

final class MatchMessages {
    private MatchMessages() {
    }

    static String stateTitle(MatchState state) {
        return switch (state) {
            case BOOTSTRAPPING -> "战场正在初始化";
            case WAITING -> "等待玩家加入";
            case WARMUP -> "战斗即将开始";
            case PLAYING -> "战斗进行中";
            case ENDING -> "本局战斗结束";
            case RESETTING -> "战场重置中";
            case STOPPING, STOPPED -> "战场正在关闭";
            case FAILED -> "战场暂不可加入";
        };
    }

    static String countdown(MatchState state, long seconds) {
        return switch (state) {
            case WARMUP -> "战斗将在 " + seconds + " 秒后开始";
            case PLAYING -> "本局剩余 " + format(seconds);
            case ENDING -> "战场将在 " + seconds + " 秒后重置";
            default -> stateTitle(state);
        };
    }

    private static String format(long seconds) {
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
