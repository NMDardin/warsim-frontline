package com.warsim.frontline.network;

public enum RejectionCode {
    INVALID_PAYLOAD(1),
    UNSUPPORTED_PROTOCOL(2),
    PLAYER_NOT_FOUND(3),
    INVALID_SOURCE(4),
    TARGET_NOT_FOUND(5),
    TARGET_UNAVAILABLE(6),
    ALREADY_CONNECTED(7),
    REQUEST_EXPIRED(8),
    PERMISSION_DENIED(9),
    INTERNAL_ERROR(10),
    TARGET_OFFLINE(11),
    TARGET_FULL(12),
    TARGET_DRAINING(13),
    TARGET_STATE_UNKNOWN(14);

    private final int id;

    RejectionCode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    static RejectionCode fromId(int id) throws ProtocolException {
        for (RejectionCode code : values()) {
            if (code.id == id) {
                return code;
            }
        }
        throw new ProtocolException("Unknown rejection code: " + id);
    }
}
