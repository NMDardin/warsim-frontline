package com.warsim.frontline.network.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.warsim.frontline.api.redis.ControlMessage;
import com.warsim.frontline.api.redis.ControlMessageType;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlMessageCodecTest {
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private final ControlMessageCodec codec = new ControlMessageCodec();

    @Test void roundTripsControlMessage() {
        ControlMessage source = message(1, NOW.plusSeconds(30), new byte[]{1, 2, 3});
        ControlMessage decoded = codec.decode(
            codec.encode(source, 16384), "official-war-01", NOW, 3, 16384
        );
        assertEquals(source.messageId(), decoded.messageId());
        assertArrayEquals(source.payload(), decoded.payload());
    }

    @Test void rejectsOversizedPayloadOnEncode() {
        assertThrows(IllegalArgumentException.class,
            () -> codec.encode(message(1, NOW.plusSeconds(30), new byte[1025]), 1024));
    }

    @Test void rejectsExpiredMessage() {
        ControlMessage expired = message(1, NOW.minusSeconds(1), new byte[0]);
        assertThrows(IllegalArgumentException.class,
            () -> codec.decode(codec.encode(expired, 16384), "official-war-01", NOW, 3, 16384));
    }

    @Test void rejectsTargetMismatch() {
        ControlMessage source = message(1, NOW.plusSeconds(30), new byte[0]);
        assertThrows(IllegalArgumentException.class,
            () -> codec.decode(codec.encode(source, 16384), "lobby-01", NOW, 3, 16384));
    }

    @Test void rejectsAttemptAboveMaximum() {
        ControlMessage source = message(4, NOW.plusSeconds(30), new byte[0]);
        assertThrows(IllegalArgumentException.class,
            () -> codec.decode(codec.encode(source, 16384), "official-war-01", NOW, 3, 16384));
    }

    @Test void rejectsUnknownMessageType() {
        ControlMessage source = message(1, NOW.plusSeconds(30), new byte[0]);
        byte[] bytes = Base64.getDecoder().decode(codec.encode(source, 16384));
        bytes[5] = 100;
        String damaged = Base64.getEncoder().encodeToString(bytes);
        assertThrows(IllegalArgumentException.class,
            () -> codec.decode(damaged, "official-war-01", NOW, 3, 16384));
    }

    @Test void payloadAccessorIsDefensive() {
        byte[] payload = {1, 2};
        ControlMessage source = message(1, NOW.plusSeconds(30), payload);
        payload[0] = 9;
        assertEquals(1, source.payload()[0]);
        byte[] returned = source.payload();
        returned[0] = 8;
        assertEquals(1, source.payload()[0]);
    }

    private static ControlMessage message(int attempt, Instant expiresAt, byte[] payload) {
        return new ControlMessage(
            1, UUID.randomUUID(), ControlMessageType.NODE_PING, "lobby-01",
            "official-war-01", UUID.randomUUID(), NOW.minusSeconds(5), expiresAt,
            attempt, null, payload
        );
    }
}
