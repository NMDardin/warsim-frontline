package com.warsim.frontline.api.objective;

import com.warsim.frontline.api.match.MatchState;
import java.time.Instant;
import java.util.List;

public interface ObjectiveService extends AutoCloseable {
    ObjectiveSystemState systemState();
    List<ObjectiveSnapshot> snapshots();
    ObjectiveSnapshot snapshot(ObjectiveId objectiveId);
    void synchronizeLifecycle(long lifecycleRevision);
    boolean process(ObjectivePresenceFrame frame, MatchState matchState);
    ObjectiveOperationResult lock(ObjectiveId objectiveId, Instant now);
    ObjectiveOperationResult unlock(ObjectiveId objectiveId, Instant now);
    ObjectiveOperationResult reset(ObjectiveId objectiveId, Instant now);
    ObjectiveOperationResult setOwner(ObjectiveId objectiveId, ObjectiveOwner owner, Instant now);
    ObjectiveMetricsSnapshot metrics();
    AutoCloseable subscribe(ObjectiveEventListener listener);
    @Override void close();
}
