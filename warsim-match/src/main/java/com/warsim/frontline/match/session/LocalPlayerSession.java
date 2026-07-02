package com.warsim.frontline.match.session;

import com.warsim.frontline.api.node.NodeIds;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalPlayerSession {
    private final UUID playerUuid;
    private final String nodeId;
    private final Instant joinedAt;
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CONNECTED);

    public LocalPlayerSession(UUID playerUuid, String nodeId, Instant joinedAt) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.nodeId = NodeIds.requireValid(nodeId);
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public String nodeId() {
        return nodeId;
    }

    public Instant joinedAt() {
        return joinedAt;
    }

    public SessionState state() {
        return state.get();
    }

    public void activate() {
        state.compareAndSet(SessionState.CONNECTED, SessionState.ACTIVE);
    }

    public void close() {
        SessionState current = state.get();
        if (current != SessionState.CLOSED) {
            state.set(SessionState.LEAVING);
            state.set(SessionState.CLOSED);
        }
    }
}
