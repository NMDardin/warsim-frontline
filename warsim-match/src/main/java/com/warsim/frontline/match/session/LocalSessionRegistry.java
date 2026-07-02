package com.warsim.frontline.match.session;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalSessionRegistry implements AutoCloseable {
    private final Map<UUID, LocalPlayerSession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final String nodeId;

    public LocalSessionRegistry(Clock clock, String nodeId) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    }

    public LocalPlayerSession join(UUID playerUuid) {
        LocalPlayerSession session = new LocalPlayerSession(playerUuid, nodeId, Instant.now(clock));
        LocalPlayerSession previous = sessions.put(playerUuid, session);
        if (previous != null) {
            previous.close();
        }
        session.activate();
        return session;
    }

    public Optional<LocalPlayerSession> find(UUID playerUuid) {
        return Optional.ofNullable(sessions.get(playerUuid));
    }

    public boolean leave(UUID playerUuid) {
        LocalPlayerSession session = sessions.remove(playerUuid);
        if (session == null) {
            return false;
        }
        session.close();
        return true;
    }

    public int size() {
        return sessions.size();
    }

    @Override
    public void close() {
        sessions.values().forEach(LocalPlayerSession::close);
        sessions.clear();
    }
}
