package com.warsim.frontline.network;

import com.warsim.frontline.api.node.NodeIds;
import java.util.Objects;
import java.util.UUID;

public record TransferRequest(
    int protocolVersion,
    UUID requestId,
    UUID playerUuid,
    String sourceNodeId,
    String targetNodeId,
    long createdAtEpochMillis
) implements NetworkMessage {
    public TransferRequest {
        requireEnvelope(protocolVersion, requestId, playerUuid, sourceNodeId, targetNodeId, createdAtEpochMillis);
    }

    @Override
    public MessageType messageType() {
        return MessageType.TRANSFER_REQUEST;
    }

    static void requireEnvelope(
        int protocolVersion,
        UUID requestId,
        UUID playerUuid,
        String sourceNodeId,
        String targetNodeId,
        long createdAtEpochMillis
    ) {
        if (protocolVersion <= 0 || protocolVersion > 255) {
            throw new IllegalArgumentException("protocolVersion must be between 1 and 255");
        }
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (isZero(requestId) || isZero(playerUuid)) {
            throw new IllegalArgumentException("UUID cannot be all zeroes");
        }
        NodeIds.requireValid(sourceNodeId);
        NodeIds.requireValid(targetNodeId);
        if (createdAtEpochMillis <= 0) {
            throw new IllegalArgumentException("createdAtEpochMillis must be positive");
        }
    }

    private static boolean isZero(UUID value) {
        return value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0;
    }
}
