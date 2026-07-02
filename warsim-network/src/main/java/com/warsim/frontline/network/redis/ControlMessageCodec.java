package com.warsim.frontline.network.redis;

import com.warsim.frontline.api.redis.ControlMessage;
import com.warsim.frontline.api.redis.ControlMessageType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class ControlMessageCodec {
    private static final int MAGIC = 0x57535231;

    public String encode(ControlMessage message, int maximumPayloadBytes) {
        if (message.payload().length > maximumPayloadBytes) {
            throw new IllegalArgumentException("Control payload exceeds maximum");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(message.protocolVersion());
                output.writeByte(message.messageType().ordinal());
                writeUuid(output, message.messageId());
                output.writeUTF(message.sourceNodeId());
                output.writeUTF(message.targetNodeId());
                writeUuid(output, message.sourceInstanceId());
                output.writeLong(message.createdAt().toEpochMilli());
                output.writeLong(message.expiresAt().toEpochMilli());
                output.writeByte(message.attempt());
                output.writeBoolean(message.correlationId() != null);
                if (message.correlationId() != null) {
                    writeUuid(output, message.correlationId());
                }
                byte[] payload = message.payload();
                output.writeInt(payload.length);
                output.write(payload);
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to encode control message", exception);
        }
    }

    public ControlMessage decode(
        String encoded,
        String expectedTarget,
        Instant now,
        int maximumAttempts,
        int maximumPayloadBytes
    ) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (input.readInt() != MAGIC) {
                    throw new IllegalArgumentException("Invalid control message magic");
                }
                int version = input.readUnsignedByte();
                int typeOrdinal = input.readUnsignedByte();
                if (typeOrdinal >= ControlMessageType.values().length) {
                    throw new IllegalArgumentException("Unknown control message type");
                }
                UUID messageId = readUuid(input);
                String source = input.readUTF();
                String target = input.readUTF();
                UUID instanceId = readUuid(input);
                Instant createdAt = Instant.ofEpochMilli(input.readLong());
                Instant expiresAt = Instant.ofEpochMilli(input.readLong());
                int attempt = input.readUnsignedByte();
                UUID correlation = input.readBoolean() ? readUuid(input) : null;
                int payloadLength = input.readInt();
                if (payloadLength < 0 || payloadLength > maximumPayloadBytes) {
                    throw new IllegalArgumentException("Invalid control payload length");
                }
                byte[] payload = input.readNBytes(payloadLength);
                if (payload.length != payloadLength || input.available() != 0) {
                    throw new IllegalArgumentException("Truncated or trailing control payload");
                }
                ControlMessage message = new ControlMessage(
                    version, messageId, ControlMessageType.values()[typeOrdinal], source, target,
                    instanceId, createdAt, expiresAt, attempt, correlation, payload
                );
                if (!expectedTarget.equals(target)) {
                    throw new IllegalArgumentException("Control target mismatch");
                }
                if (!expiresAt.isAfter(now)) {
                    throw new IllegalArgumentException("Control message expired");
                }
                if (attempt > maximumAttempts) {
                    throw new IllegalArgumentException("Control message attempts exceeded");
                }
                return message;
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid control message: " + exception.getMessage(), exception);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
