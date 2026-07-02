package com.warsim.frontline.network;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record TransferRejected(
    int protocolVersion,
    UUID requestId,
    UUID playerUuid,
    String sourceNodeId,
    String targetNodeId,
    long createdAtEpochMillis,
    RejectionCode rejectionCode,
    String userMessage
) implements NetworkMessage {
    public static final int MAX_USER_MESSAGE_BYTES = 512;

    public TransferRejected {
        TransferRequest.requireEnvelope(
            protocolVersion, requestId, playerUuid, sourceNodeId, targetNodeId, createdAtEpochMillis
        );
        Objects.requireNonNull(rejectionCode, "rejectionCode");
        Objects.requireNonNull(userMessage, "userMessage");
        int length = userMessage.getBytes(StandardCharsets.UTF_8).length;
        if (length < 1 || length > MAX_USER_MESSAGE_BYTES) {
            throw new IllegalArgumentException("userMessage must be 1-512 UTF-8 bytes");
        }
    }

    @Override
    public MessageType messageType() {
        return MessageType.TRANSFER_REJECTED;
    }
}
