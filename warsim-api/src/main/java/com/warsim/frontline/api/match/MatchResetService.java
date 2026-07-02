package com.warsim.frontline.api.match;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface MatchResetService {
    CompletableFuture<MatchResetResult> reset(MatchResetContext context);

    static MatchResetService noOp() {
        return context -> CompletableFuture.completedFuture(MatchResetResult.success());
    }
}
