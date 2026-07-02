package com.warsim.frontline.velocity;

import com.warsim.frontline.api.node.NodeIds;
import com.warsim.frontline.network.MessageCodec;
import com.warsim.frontline.network.redis.RedisConfiguration;
import com.warsim.frontline.network.redis.RedisEnvironmentOverrides;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;

record VelocityNetworkConfig(
    String channel,
    int maximumMessageBytes,
    long requestTimeoutMillis,
    Set<String> allowedSources,
    Set<String> allowedTargets,
    String proxyNodeId,
    RedisConfiguration redis
) {
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    VelocityNetworkConfig {
        if (channel == null || channel.length() > 64 || !CHANNEL.matcher(channel).matches()) {
            throw new IllegalArgumentException("Invalid channel");
        }
        if (maximumMessageBytes < 1024 || maximumMessageBytes > 32767) {
            throw new IllegalArgumentException("maximum-message-bytes must be 1024-32767");
        }
        if (requestTimeoutMillis < 1000 || requestTimeoutMillis > 30000) {
            throw new IllegalArgumentException("request-timeout-millis must be 1000-30000");
        }
        allowedSources = Set.copyOf(allowedSources);
        allowedTargets = Set.copyOf(allowedTargets);
        NodeIds.requireValid(proxyNodeId);
        if (allowedSources.isEmpty() || allowedSources.stream().anyMatch(id -> !NodeIds.isValid(id))) {
            throw new IllegalArgumentException("allowed-sources contains invalid node IDs");
        }
        if (allowedTargets.isEmpty() || allowedTargets.stream().anyMatch(id -> !NodeIds.isValid(id))) {
            throw new IllegalArgumentException("allowed-targets contains invalid node IDs");
        }
    }

    static VelocityNetworkConfig defaults() {
        return new VelocityNetworkConfig(
            MessageCodec.DEFAULT_CHANNEL,
            MessageCodec.DEFAULT_MAXIMUM_MESSAGE_BYTES,
            5000,
            Set.of("lobby-01"),
            Set.of("official-war-01"),
            "velocity-01",
            RedisConfiguration.defaults()
        );
    }

    static VelocityNetworkConfig load(Path dataDirectory, Logger logger) {
        Path file = dataDirectory.resolve("network.properties");
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(file)) {
                writeDefaults(file);
                return defaults();
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
            RedisConfiguration redis = RedisEnvironmentOverrides.apply(
                new RedisConfiguration(
                    Boolean.parseBoolean(properties.getProperty("redis.enabled", "false")),
                    properties.getProperty("redis.uri", "redis://127.0.0.1:6379"),
                    properties.getProperty("redis.username", ""),
                    properties.getProperty("redis.password", ""),
                    Integer.parseInt(properties.getProperty("redis.database", "0")),
                    properties.getProperty("redis.namespace", "warsim:frontline:v1"),
                    Boolean.parseBoolean(properties.getProperty("redis.tls.enabled", "false")),
                    Boolean.parseBoolean(properties.getProperty("redis.tls.verify-hostname", "true")),
                    Long.parseLong(properties.getProperty("redis.connection.timeout-millis", "5000")),
                    Long.parseLong(properties.getProperty("redis.connection.reconnect-delay-millis", "2000")),
                    Long.parseLong(properties.getProperty("redis.heartbeat.interval-millis", "5000")),
                    Long.parseLong(properties.getProperty("redis.heartbeat.ttl-millis", "15000")),
                    Boolean.parseBoolean(properties.getProperty("redis.streams.enabled", "true")),
                    Long.parseLong(properties.getProperty("redis.streams.block-millis", "2000")),
                    Integer.parseInt(properties.getProperty("redis.streams.batch-size", "20")),
                    Long.parseLong(properties.getProperty("redis.streams.claim-idle-millis", "15000")),
                    Long.parseLong(properties.getProperty("redis.streams.message-ttl-millis", "30000")),
                    Integer.parseInt(properties.getProperty("redis.streams.maximum-attempts", "3")),
                    Integer.parseInt(properties.getProperty("redis.streams.deduplication-ttl-seconds", "120")),
                    Integer.parseInt(properties.getProperty("redis.streams.maximum-payload-bytes", "16384"))
                ),
                System.getenv()
            );
            return new VelocityNetworkConfig(
                properties.getProperty("channel", MessageCodec.DEFAULT_CHANNEL),
                Integer.parseInt(properties.getProperty("maximum-message-bytes", "8192")),
                Long.parseLong(properties.getProperty("request-timeout-millis", "5000")),
                parseNodes(properties.getProperty("allowed-sources", "lobby-01")),
                parseNodes(properties.getProperty("allowed-targets", "official-war-01")),
                properties.getProperty("proxy-node-id", "velocity-01"),
                redis
            );
        } catch (IOException | RuntimeException exception) {
            logger.error(
                "[warsim-velocity] 配置加载或验证失败，已使用安全默认值：{}",
                exception.getMessage(),
                exception
            );
            return defaults();
        }
    }

    private static Set<String> parseNodes(String value) {
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    private static void writeDefaults(Path file) throws IOException {
        Properties defaults = new Properties();
        defaults.setProperty("channel", MessageCodec.DEFAULT_CHANNEL);
        defaults.setProperty("maximum-message-bytes", "8192");
        defaults.setProperty("request-timeout-millis", "5000");
        defaults.setProperty("allowed-sources", "lobby-01");
        defaults.setProperty("allowed-targets", "official-war-01");
        defaults.setProperty("proxy-node-id", "velocity-01");
        defaults.setProperty("redis.enabled", "false");
        defaults.setProperty("redis.uri", "redis://127.0.0.1:6379");
        defaults.setProperty("redis.username", "");
        defaults.setProperty("redis.password", "");
        defaults.setProperty("redis.database", "0");
        defaults.setProperty("redis.namespace", "warsim:frontline:v1");
        defaults.setProperty("redis.tls.enabled", "false");
        defaults.setProperty("redis.tls.verify-hostname", "true");
        defaults.setProperty("redis.connection.timeout-millis", "5000");
        defaults.setProperty("redis.connection.reconnect-delay-millis", "2000");
        defaults.setProperty("redis.heartbeat.interval-millis", "5000");
        defaults.setProperty("redis.heartbeat.ttl-millis", "15000");
        defaults.setProperty("redis.streams.enabled", "true");
        defaults.setProperty("redis.streams.block-millis", "2000");
        defaults.setProperty("redis.streams.batch-size", "20");
        defaults.setProperty("redis.streams.claim-idle-millis", "15000");
        defaults.setProperty("redis.streams.message-ttl-millis", "30000");
        defaults.setProperty("redis.streams.maximum-attempts", "3");
        defaults.setProperty("redis.streams.deduplication-ttl-seconds", "120");
        defaults.setProperty("redis.streams.maximum-payload-bytes", "16384");
        try (OutputStream output = Files.newOutputStream(file)) {
            defaults.store(output, "WarSim Frontline Velocity network settings - contains no proxy secret");
        }
    }
}
