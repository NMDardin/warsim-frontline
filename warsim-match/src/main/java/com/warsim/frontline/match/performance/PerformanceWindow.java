package com.warsim.frontline.match.performance;

import com.warsim.frontline.api.performance.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.UUID;

final class PerformanceWindow {
    private final PerformanceMetricId metricId;
    private final PerformanceComponent component;
    private final long[] samples;
    private int next;
    private int size;
    private long sampleCount;
    private long successCount;
    private long failureCount;
    private long lastNanos = -1;
    private long minNanos = Long.MAX_VALUE;
    private long maxNanos;
    private long totalNanos;
    private Instant firstSampleAt;
    private Instant lastSampleAt;
    private Instant lastSlowSampleAt;
    private UUID matchId;
    private long revision;

    PerformanceWindow(PerformanceMetricId metricId, PerformanceComponent component, int capacity) {
        this.metricId = metricId;
        this.component = component;
        this.samples = new long[capacity];
    }

    void add(PerformanceSample sample, long slowThresholdNanos) {
        matchId = sample.matchId();
        revision = sample.lifecycleRevision();
        samples[next] = sample.durationNanos();
        next = (next + 1) % samples.length;
        if (size < samples.length) size++;
        sampleCount++;
        if (sample.successful()) successCount++;
        else failureCount++;
        lastNanos = sample.durationNanos();
        minNanos = Math.min(minNanos, sample.durationNanos());
        maxNanos = Math.max(maxNanos, sample.durationNanos());
        totalNanos += sample.durationNanos();
        if (firstSampleAt == null) firstSampleAt = sample.sampledAt();
        lastSampleAt = sample.sampledAt();
        if (sample.durationNanos() >= slowThresholdNanos && slowThresholdNanos > 0) {
            lastSlowSampleAt = sample.sampledAt();
        }
    }

    PerformanceMetricSnapshot snapshot() {
        long[] current = currentSamples();
        PerformancePercentiles percentiles = current.length < 2
            ? PerformancePercentiles.unavailable()
            : nearestRank(current);
        OptionalLong last = lastNanos < 0 ? OptionalLong.empty() : OptionalLong.of(lastNanos);
        OptionalLong min = sampleCount == 0 ? OptionalLong.empty() : OptionalLong.of(minNanos);
        OptionalLong max = sampleCount == 0 ? OptionalLong.empty() : OptionalLong.of(maxNanos);
        OptionalLong mean = sampleCount == 0 ? OptionalLong.empty()
            : OptionalLong.of(Math.round((double) totalNanos / sampleCount));
        double perSecond = 0;
        if (firstSampleAt != null && lastSampleAt != null && lastSampleAt.isAfter(firstSampleAt)) {
            double seconds = Math.max(.001, Duration.between(firstSampleAt, lastSampleAt).toNanos() / 1_000_000_000.0);
            perSecond = sampleCount / seconds;
        }
        return new PerformanceMetricSnapshot(
            metricId, component, matchId, revision, sampleCount, successCount, failureCount,
            last, min, max, mean, percentiles, perSecond, lastSampleAt, lastSlowSampleAt
        );
    }

    private long[] currentSamples() {
        long[] copy = new long[size];
        for (int index = 0; index < size; index++) {
            int actual = (next - size + index + samples.length) % samples.length;
            copy[index] = samples[actual];
        }
        return copy;
    }

    private static PerformancePercentiles nearestRank(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return new PerformancePercentiles(
            OptionalLong.of(rank(sorted, .50)),
            OptionalLong.of(rank(sorted, .95)),
            OptionalLong.of(rank(sorted, .99))
        );
    }

    private static long rank(long[] sorted, double percentile) {
        int rank = (int) Math.ceil(percentile * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }
}
