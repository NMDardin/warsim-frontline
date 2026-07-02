package com.warsim.frontline.api.performance;

/** Low-overhead span used while sampling is disabled. */
public enum NoOpPerformanceSpan implements PerformanceSpan {
    INSTANCE;

    @Override public void success() {}

    @Override public void failure() {}
}
