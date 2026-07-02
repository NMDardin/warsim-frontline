package com.warsim.frontline.network;

import com.warsim.frontline.api.node.NodeIds;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class MessageCodec {
    public static final String DEFAULT_CHANNEL = "warsim:control";
    public static final int DEFAULT_MAXIMUM_MESSAGE_BYTES = 8192;
    private static final int MAGIC = 0x57534631;

    public byte[] encode(NetworkMessage message, int maximumMessageBytes) throws ProtocolException {
        Objects.requireNonNull(message, "message");
        validateMaximum(maximumMessageBytes);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(MAGIC);
                output.writeByte(message.protocolVersion());
                output.writeByte(message.messageType().id());
                writeUuid(output, message.requestId());
                writeUuid(output, message.playerUuid());
                writeString(output, message.sourceNodeId(), NodeIds.MAX_LENGTH);
                writeString(output, message.targetNodeId(), NodeIds.MAX_LENGTH);
                output.writeLong(message.createdAtEpochMillis());
                if (message instanceof TransferRejected rejected) {
                    output.writeByte(rejected.rejectionCode().id());
                    writeString(output, rejected.userMessage(), TransferRejected.MAX_USER_MESSAGE_BYTES);
                }
            }
            byte[] payload = buffer.toByteArray();
            if (payload.length > maximumMessageBytes) {
                throw new ProtocolException("Message exceeds maximum size");
            }
            return payload;
        } catch (IOException exception) {
            throw new ProtocolException("Unable to encode message", exception);
        }
    }

    public NetworkMessage decode(byte[] payload, DecodePolicy policy) throws ProtocolException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(policy, "policy");
        validateMaximum(policy.maximumMessageBytes());
        if (payload.length > policy.maximumMessageBytes()) {
            throw new ProtocolException("Message exceeds maximum size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new ProtocolException("Invalid magic");
            }
            int version = input.readUnsignedByte();
            if (version != ProtocolVersion.CURRENT) {
                throw new ProtocolException("Unsupported protocol version: " + version);
            }
            MessageType type = MessageType.fromId(input.readUnsignedByte());
            UUID requestId = readUuid(input);
            UUID playerUuid = readUuid(input);
            String source = readString(input, NodeIds.MAX_LENGTH);
            String target = readString(input, NodeIds.MAX_LENGTH);
            long createdAt = input.readLong();
            validateDecodedEnvelope(requestId, playerUuid, source, target, createdAt);

            NetworkMessage message = switch (type) {
                case TRANSFER_REQUEST ->
                    new TransferRequest(version, requestId, playerUuid, source, target, createdAt);
                case TRANSFER_ACCEPTED ->
                    new TransferAccepted(version, requestId, playerUuid, source, target, createdAt);
                case TRANSFER_REJECTED -> {
                    RejectionCode code = RejectionCode.fromId(input.readUnsignedByte());
                    String userMessage = readString(input, TransferRejected.MAX_USER_MESSAGE_BYTES);
                    yield new TransferRejected(
                        version, requestId, playerUuid, source, target, createdAt, code, userMessage
                    );
                }
            };
            if (input.available() != 0) {
                throw new ProtocolException("Trailing bytes are not allowed");
            }
            if (message instanceof TransferRequest) {
                long age = policy.clock().millis() - createdAt;
                if (age < -policy.maximumClockSkewMillis() || age > policy.maximumRequestAgeMillis()) {
                    throw new ProtocolException("Transfer request expired");
                }
            }
            return message;
        } catch (EOFException exception) {
            throw new ProtocolException("Truncated payload", exception);
        } catch (ProtocolException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new ProtocolException("Invalid payload", exception);
        }
    }

    private static void validateDecodedEnvelope(
        UUID requestId,
        UUID playerUuid,
        String source,
        String target,
        long createdAt
    ) throws ProtocolException {
        if (isZero(requestId) || isZero(playerUuid)) {
            throw new ProtocolException("Invalid UUID");
        }
        if (!NodeIds.isValid(source) || !NodeIds.isValid(target)) {
            throw new ProtocolException("Invalid node ID");
        }
        if (createdAt <= 0) {
            throw new ProtocolException("Invalid creation time");
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeString(DataOutputStream output, String value, int maximumBytes)
        throws IOException, ProtocolException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > maximumBytes) {
            throw new ProtocolException("String length is outside protocol limits");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximumBytes)
        throws IOException, ProtocolException {
        int length = input.readUnsignedShort();
        if (length < 1 || length > maximumBytes) {
            throw new ProtocolException("String length is outside protocol limits");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Truncated string");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException("Invalid UTF-8", exception);
        }
    }

    private static boolean isZero(UUID value) {
        return value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0;
    }

    private static void validateMaximum(int maximumMessageBytes) {
        if (maximumMessageBytes < 1024 || maximumMessageBytes > 32767) {
            throw new IllegalArgumentException("maximumMessageBytes must be 1024-32767");
        }
    }

    public record DecodePolicy(
        int maximumMessageBytes,
        long maximumRequestAgeMillis,
        long maximumClockSkewMillis,
        Clock clock
    ) {
        public DecodePolicy {
            if (maximumRequestAgeMillis < 1000 || maximumRequestAgeMillis > 30000) {
                throw new IllegalArgumentException("maximumRequestAgeMillis must be 1000-30000");
            }
            if (maximumClockSkewMillis < 0 || maximumClockSkewMillis > 30000) {
                throw new IllegalArgumentException("maximumClockSkewMillis must be 0-30000");
            }
            Objects.requireNonNull(clock, "clock");
        }

        public static DecodePolicy defaults(Clock clock) {
            return new DecodePolicy(DEFAULT_MAXIMUM_MESSAGE_BYTES, 5000, 1000, clock);
        }
    }
}
