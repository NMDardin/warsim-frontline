package com.warsim.frontline.api.objective;

import java.time.Instant;

public record ObjectiveMetricsSnapshot(
    long scanCycles,
    long scannedPlayers,
    int activeObjectives,
    long contestedTransitions,
    long neutralizations,
    long capturesByAttackers,
    long capturesByDefenders,
    long staleFramesRejected,
    long duplicateCaptureEventsRejected,
    long invalidPresenceFrames,
    long displayUpdates,
    long displayRemovals,
    long listenerFailures,
    long lastScanDurationNanos,
    long maximumScanDurationNanos,
    Instant lastCaptureAt
) {
}
