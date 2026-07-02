package com.warsim.frontline.api.performance;

/** One-shot timing span. */
public interface PerformanceSpan extends AutoCloseable {
    void success();

    void failure();

    @Override
    default void close() {
        success();
    }
}
