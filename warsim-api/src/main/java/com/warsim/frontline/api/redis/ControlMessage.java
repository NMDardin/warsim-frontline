package com.warsim.frontline.api.redis;

import com.warsim.frontline.api.node.NodeIds;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record ControlMessage(
    int protocolVersion,
    UUID messageId,
    ControlMessageType messageType,
    String sourceNodeId,
    String targetNodeId,
    UUID sourceInstanceId,
    Instant createdAt,
    Instant expiresAt,
    int attempt,
    UUID correlationId,
    byte[] payload
) {
    public ControlMessage {
        if (protocolVersion != 1) {
            throw new IllegalArgumentException("Unsupported protocol version");
        }
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(messageType, "messageType");
        NodeIds.requireValid(sourceNodeId);
        NodeIds.requireValid(targetNodeId);
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
