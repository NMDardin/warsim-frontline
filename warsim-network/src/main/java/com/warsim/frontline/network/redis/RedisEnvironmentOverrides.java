package com.warsim.frontline.network.redis;

import java.util.Map;

public final class RedisEnvironmentOverrides {
    private RedisEnvironmentOverrides() {
    }

    public static RedisConfiguration apply(RedisConfiguration config, Map<String, String> environment) {
        return config.withConnection(
            nonBlank(environment.get("WARSIM_REDIS_URI"), config.uri()),
            nonBlank(environment.get("WARSIM_REDIS_USERNAME"), config.username()),
            nonBlank(environment.get("WARSIM_REDIS_PASSWORD"), config.password())
        );
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
