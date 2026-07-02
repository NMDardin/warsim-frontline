package com.warsim.frontline.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageCodecTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private MessageCodec codec;
    private MessageCodec.DecodePolicy policy;

    @BeforeEach
    void setUp() {
        codec = new MessageCodec();
        policy = MessageCodec.DecodePolicy.defaults(
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
        );
    }

    @Test
    void requestRoundTrips() throws Exception {
        TransferRequest request = request(NOW);
        assertEquals(request, codec.decode(codec.encode(request, 8192), policy));
    }

    @Test
    void acceptedRoundTrips() throws Exception {
        TransferAccepted accepted = new TransferAccepted(
            1, REQUEST_ID, PLAYER_ID, "lobby-01", "official-war-01", NOW
        );
        assertEquals(accepted, codec.decode(codec.encode(accepted, 8192), policy));
    }

    @Test
    void rejectedRoundTrips() throws Exception {
        TransferRejected rejected = new TransferRejected(
            1, REQUEST_ID, PLAYER_ID, "lobby-01", "official-war-01", NOW,
            RejectionCode.TARGET_UNAVAILABLE, "目标战场暂不可用。"
        );
        assertEquals(rejected, codec.decode(codec.encode(rejected, 8192), policy));
    }

    @Test
    void invalidMagicIsRejected() throws Exception {
        byte[] payload = codec.encode(request(NOW), 8192);
        payload[0] = 0;
        assertThrows(ProtocolException.class, () -> codec.decode(payload, policy));
    }

    @Test
    void unsupportedVersionIsRejected() throws Exception {
        byte[] payload = codec.encode(request(NOW), 8192);
        payload[4] = 2;
        assertThrows(ProtocolException.class, () -> codec.decode(payload, policy));
    }

    @Test
    void unknownMessageTypeIsRejected() throws Exception {
        byte[] payload = codec.encode(request(NOW), 8192);
        payload[5] = 99;
        assertThrows(ProtocolException.class, () -> codec.decode(payload, policy));
    }

    @Test
    void overlongNodeIdIsRejectedBeforeEncoding() {
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest(
            1, REQUEST_ID, PLAYER_ID, "a".repeat(49), "official-war-01", NOW
        ));
    }

    @Test
    void illegalNodeIdIsRejectedDuringDecode() throws Exception {
        byte[] payload = codec.encode(request(NOW), 8192);
        payload[40] = 'L';
        assertThrows(ProtocolException.class, () -> codec.decode(payload, policy));
    }

    @Test
    void expiredRequestIsRejected() throws Exception {
        byte[] payload = codec.encode(request(NOW - 5001), 8192);
        assertThrows(ProtocolException.class, () -> codec.decode(payload, policy));
    }

    @Test
    void truncatedPayloadIsRejected() throws Exception {
        byte[] payload = codec.encode(request(NOW), 8192);
        byte[] truncated = Arrays.copyOf(payload, payload.length - 2);
        assertThrows(ProtocolException.class, () -> codec.decode(truncated, policy));
    }

    @Test
    void oversizedPayloadIsRejected() {
        byte[] oversized = new byte[8193];
        assertThrows(ProtocolException.class, () -> codec.decode(oversized, policy));
    }

    @Test
    void decodedTypeIsImmutableRecord() throws Exception {
        NetworkMessage decoded = codec.decode(codec.encode(request(NOW), 8192), policy);
        assertInstanceOf(TransferRequest.class, decoded);
    }

    private static TransferRequest request(long createdAt) {
        return new TransferRequest(
            1, REQUEST_ID, PLAYER_ID, "lobby-01", "official-war-01", createdAt
        );
    }
}
