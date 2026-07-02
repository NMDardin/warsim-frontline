package com.warsim.frontline.network.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisConfigurationTest {
    @Test void defaultsAreDisabledAndValid() {
        RedisConfiguration config = RedisConfiguration.defaults();
        RedisConfigurationValidator.validate(config);
        assertFalse(config.enabled());
    }

    @Test void rejectsNonRedisUri() {
        assertThrows(IllegalArgumentException.class, () ->
            RedisConfigurationValidator.validate(copy("http://localhost:6379", "warsim:frontline:v1", 5000, 15000, 20, 3, 16384)));
    }

    @Test void rejectsInvalidNamespace() {
        assertThrows(IllegalArgumentException.class, () ->
            RedisConfigurationValidator.validate(copy("redis://localhost:6379", "War Sim", 5000, 15000, 20, 3, 16384)));
    }

    @Test void rejectsHeartbeatBelowBoundary() {
        assertThrows(IllegalArgumentException.class, () ->
            RedisConfigurationValidator.validate(copy("redis://localhost:6379", "warsim:v1", 999, 15000, 20, 3, 16384)));
    }

    @Test void requiresTtlAtLeastTwiceHeartbeat() {
        assertThrows(IllegalArgumentException.class, () ->
            RedisConfigurationValidator.validate(copy("redis://localhost:6379", "warsim:v1", 5000, 9999, 20, 3, 16384)));
    }

    @Test void rejectsStreamBatchAboveBoundary() {
        assertThrows(IllegalArgumentException.class, () ->
            RedisConfigurationValidator.validate(copy("redis://localhost:6379", "warsim:v1", 5000, 15000, 101, 3, 16384)));
    }

    @Test void rejectsAttemptsAboveBoundary() {
        assertThrows(IllegalArgumentException.class, () ->
            RedisConfigurationValidator.validate(copy("redis://localhost:6379", "warsim:v1", 5000, 15000, 20, 11, 16384)));
    }

    @Test void rejectsPayloadBelowBoundary() {
        assertThrows(IllegalArgumentException.class, () ->
            RedisConfigurationValidator.validate(copy("redis://localhost:6379", "warsim:v1", 5000, 15000, 20, 3, 1000)));
    }

    @Test void nonBlankEnvironmentOverridesValues() {
        RedisConfiguration result = RedisEnvironmentOverrides.apply(
            RedisConfiguration.defaults(),
            Map.of(
                "WARSIM_REDIS_URI", "rediss://redis.internal:6380",
                "WARSIM_REDIS_USERNAME", "frontline",
                "WARSIM_REDIS_PASSWORD", "secret"
            )
        );
        assertEquals("rediss://redis.internal:6380", result.uri());
        assertEquals("frontline", result.username());
        assertEquals("secret", result.password());
    }

    @Test void blankEnvironmentDoesNotOverrideValues() {
        RedisConfiguration original = RedisConfiguration.defaults();
        RedisConfiguration result = RedisEnvironmentOverrides.apply(
            original,
            Map.of("WARSIM_REDIS_URI", " ", "WARSIM_REDIS_USERNAME", "", "WARSIM_REDIS_PASSWORD", "")
        );
        assertEquals(original.uri(), result.uri());
        assertEquals(original.password(), result.password());
    }

    @Test void sanitizerRemovesCredentialsAndQuery() {
        String value = RedisUriSanitizer.sanitize(
            "redis://user:secret@localhost:6379/0?password=secret"
        );
        assertFalse(value.contains("secret"));
        assertEquals("redis://localhost:6379/0", value);
    }

    @Test void configurationStringNeverContainsPassword() {
        RedisConfiguration configured = RedisConfiguration.defaults()
            .withConnection("redis://localhost:6379", "frontline", "secret-value");
        assertFalse(configured.toString().contains("secret-value"));
    }

    private static RedisConfiguration copy(
        String uri, String namespace, long heartbeat, long ttl,
        int batch, int attempts, int payload
    ) {
        RedisConfiguration d = RedisConfiguration.defaults();
        return new RedisConfiguration(
            d.enabled(), uri, d.username(), d.password(), d.database(), namespace,
            d.tlsEnabled(), d.verifyHostname(), d.connectionTimeoutMillis(),
            d.reconnectDelayMillis(), heartbeat, ttl, d.streamsEnabled(),
            d.streamBlockMillis(), batch, d.claimIdleMillis(), d.messageTtlMillis(),
            attempts, d.deduplicationTtlSeconds(), payload
        );
    }
}
