package com.warsim.frontline.api;

/**
 * A closeable WarSim component with an explicit lifecycle.
 */
public interface WarSimModule extends AutoCloseable {
    String name();

    ModuleState state();

    void start() throws Exception;

    void stop() throws Exception;

    @Override
    default void close() throws Exception {
        stop();
    }
}
