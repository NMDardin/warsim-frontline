package com.warsim.frontline.network;

public enum MessageType {
    TRANSFER_REQUEST(1),
    TRANSFER_ACCEPTED(2),
    TRANSFER_REJECTED(3);

    private final int id;

    MessageType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    static MessageType fromId(int id) throws ProtocolException {
        for (MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new ProtocolException("Unknown message type: " + id);
    }
}
