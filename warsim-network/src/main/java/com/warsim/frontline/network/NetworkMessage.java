package com.warsim.frontline.network;

import java.util.UUID;

public sealed interface NetworkMessage permits TransferRequest, TransferAccepted, TransferRejected {
    int protocolVersion();

    MessageType messageType();

    UUID requestId();

    UUID playerUuid();

    String sourceNodeId();

    String targetNodeId();

    long createdAtEpochMillis();
}
