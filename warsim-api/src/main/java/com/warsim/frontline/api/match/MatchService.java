package com.warsim.frontline.api.match;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface MatchService extends AutoCloseable {
    MatchSnapshot snapshot();
    MatchMetricsSnapshot metrics();
    List<MatchSummary> history();
    MatchOperationResult start(boolean force);
    MatchOperationResult end(MatchEndReason reason, String summary);
    MatchOperationResult reset();
    MatchOperationResult recover();
    MatchParticipantJoinResult participantJoined(UUID playerUuid, String name, Instant joinedAt);
    Optional<MatchParticipant> participant(UUID playerUuid);
    boolean participantLeft(UUID playerUuid, Instant leftAt);
    AutoCloseable subscribe(MatchEventListener listener, boolean matchScoped);
    void tick(long monotonicNanos, Instant wallTime);
    void stop();
    @Override void close();
}
