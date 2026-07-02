package com.warsim.frontline.match;

import com.warsim.frontline.api.match.MatchMetricsSnapshot;
import com.warsim.frontline.api.match.MatchState;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class MutableMatchMetrics {
    final AtomicLong created = new AtomicLong();
    final AtomicLong completed = new AtomicLong();
    final AtomicLong failed = new AtomicLong();
    final AtomicLong transitions = new AtomicLong();
    final AtomicLong invalidTransitions = new AtomicLong();
    final AtomicInteger peakParticipants = new AtomicInteger();
    final AtomicLong warmupCancellations = new AtomicLong();
    final AtomicLong administratorStarts = new AtomicLong();
    final AtomicLong administratorEnds = new AtomicLong();
    final AtomicLong resets = new AtomicLong();
    final AtomicLong resetFailures = new AtomicLong();
    final AtomicLong staleTasks = new AtomicLong();
    final AtomicLong listenerFailures = new AtomicLong();
    final AtomicReference<Instant> lastTransition = new AtomicReference<>();
    final AtomicReference<Instant> lastStarted = new AtomicReference<>();
    final AtomicReference<Instant> lastEnded = new AtomicReference<>();

    MatchMetricsSnapshot snapshot(MatchState state, long revision, int active) {
        return new MatchMetricsSnapshot(
            state, revision, created.get(), completed.get(), failed.get(), transitions.get(),
            invalidTransitions.get(), active, peakParticipants.get(),
            warmupCancellations.get(), administratorStarts.get(), administratorEnds.get(),
            resets.get(), resetFailures.get(), staleTasks.get(), listenerFailures.get(),
            lastTransition.get(), lastStarted.get(), lastEnded.get()
        );
    }
}
