package com.warsim.frontline.network.redis;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public final class RedisConfigurationValidator {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9:_-]{1,96}");

    private RedisConfigurationValidator() {
    }

    public static void validate(RedisConfiguration config) {
        try {
            URI uri = new URI(config.uri());
            require("redis".equals(uri.getScheme()) || "rediss".equals(uri.getScheme()), "Redis URI scheme");
            require(uri.getHost() != null && uri.getPort() > 0, "Redis URI host and port");
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid Redis URI", exception);
        }
        require(config.database() >= 0 && config.database() <= 15, "Redis database must be 0-15");
        require(NAMESPACE.matcher(config.namespace()).matches(), "Invalid Redis namespace");
        requireRange(config.connectionTimeoutMillis(), 1000, 30000, "connection timeout");
        requireRange(config.heartbeatIntervalMillis(), 1000, 30000, "heartbeat interval");
        require(config.heartbeatTtlMillis() >= config.heartbeatIntervalMillis() * 2,
            "heartbeat TTL must be at least twice interval");
        require(config.heartbeatTtlMillis() <= 120000, "heartbeat TTL too large");
        requireRange(config.streamBlockMillis(), 100, 10000, "stream block");
        require(config.streamBatchSize() >= 1 && config.streamBatchSize() <= 100, "stream batch size");
        require(config.maximumAttempts() >= 1 && config.maximumAttempts() <= 10, "maximum attempts");
        require(config.maximumPayloadBytes() >= 1024 && config.maximumPayloadBytes() <= 65536,
            "maximum payload");
        require(config.deduplicationTtlSeconds() * 1000L > config.messageTtlMillis(),
            "deduplication TTL must exceed message TTL");
    }

    private static void requireRange(long value, long minimum, long maximum, String name) {
        require(value >= minimum && value <= maximum, name + " out of range");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
