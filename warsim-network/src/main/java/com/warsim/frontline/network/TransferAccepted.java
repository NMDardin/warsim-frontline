package com.warsim.frontline.network;

import java.util.UUID;

public record TransferAccepted(
    int protocolVersion,
    UUID requestId,
    UUID playerUuid,
    String sourceNodeId,
    String targetNodeId,
    long createdAtEpochMillis
) implements NetworkMessage {
    public TransferAccepted {
        TransferRequest.requireEnvelope(
            protocolVersion, requestId, playerUuid, sourceNodeId, targetNodeId, createdAtEpochMillis
        );
    }

    @Override
    public MessageType messageType() {
        return MessageType.TRANSFER_ACCEPTED;
    }
}
