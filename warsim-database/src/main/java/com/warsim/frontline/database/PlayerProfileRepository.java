package com.warsim.frontline.database;

import com.warsim.frontline.api.database.PlayerProfileService;
import java.util.concurrent.CompletableFuture;

public interface PlayerProfileRepository extends PlayerProfileService {
    CompletableFuture<Boolean> healthCheck();
}
