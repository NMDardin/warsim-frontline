package com.warsim.frontline.network.redis;

public record RedisConfiguration(
    boolean enabled,
    String uri,
    String username,
    String password,
    int database,
    String namespace,
    boolean tlsEnabled,
    boolean verifyHostname,
    long connectionTimeoutMillis,
    long reconnectDelayMillis,
    long heartbeatIntervalMillis,
    long heartbeatTtlMillis,
    boolean streamsEnabled,
    long streamBlockMillis,
    int streamBatchSize,
    long claimIdleMillis,
    long messageTtlMillis,
    int maximumAttempts,
    int deduplicationTtlSeconds,
    int maximumPayloadBytes
) {
    public static RedisConfiguration defaults() {
        return new RedisConfiguration(
            false, "redis://127.0.0.1:6379", "", "", 0, "warsim:frontline:v1",
            false, true, 5000, 2000, 5000, 15000, true, 2000, 20,
            15000, 30000, 3, 120, 16384
        );
    }

    public RedisConfiguration withEnabled(boolean value) {
        return copy(value, uri, username, password);
    }

    public RedisConfiguration withConnection(String newUri, String newUsername, String newPassword) {
        return copy(enabled, newUri, newUsername, newPassword);
    }

    private RedisConfiguration copy(
        boolean newEnabled, String newUri, String newUsername, String newPassword
    ) {
        return new RedisConfiguration(
            newEnabled, newUri, newUsername, newPassword, database, namespace, tlsEnabled,
            verifyHostname, connectionTimeoutMillis, reconnectDelayMillis, heartbeatIntervalMillis,
            heartbeatTtlMillis, streamsEnabled, streamBlockMillis, streamBatchSize, claimIdleMillis,
            messageTtlMillis, maximumAttempts, deduplicationTtlSeconds, maximumPayloadBytes
        );
    }

    @Override
    public String toString() {
        return "RedisConfiguration[enabled=" + enabled
            + ", uri=" + RedisUriSanitizer.sanitize(uri)
            + ", username=" + (username.isBlank() ? "<empty>" : "<configured>")
            + ", password=<redacted>"
            + ", database=" + database
            + ", namespace=" + namespace
            + ", tlsEnabled=" + tlsEnabled
            + ", verifyHostname=" + verifyHostname
            + ", connectionTimeoutMillis=" + connectionTimeoutMillis
            + ", reconnectDelayMillis=" + reconnectDelayMillis
            + ", heartbeatIntervalMillis=" + heartbeatIntervalMillis
            + ", heartbeatTtlMillis=" + heartbeatTtlMillis
            + ", streamsEnabled=" + streamsEnabled
            + ", streamBlockMillis=" + streamBlockMillis
            + ", streamBatchSize=" + streamBatchSize
            + ", claimIdleMillis=" + claimIdleMillis
            + ", messageTtlMillis=" + messageTtlMillis
            + ", maximumAttempts=" + maximumAttempts
            + ", deduplicationTtlSeconds=" + deduplicationTtlSeconds
            + ", maximumPayloadBytes=" + maximumPayloadBytes
            + "]";
    }
}
