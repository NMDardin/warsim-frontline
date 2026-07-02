package com.warsim.frontline.common.lifecycle;

import com.warsim.frontline.api.ModuleState;
import com.warsim.frontline.api.WarSimModule;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, idempotent lifecycle base for platform-neutral modules.
 */
public abstract class AbstractWarSimModule implements WarSimModule {
    private final String name;
    private final AtomicReference<ModuleState> state = new AtomicReference<>(ModuleState.CREATED);

    protected AbstractWarSimModule(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final ModuleState state() {
        return state.get();
    }

    @Override
    public final synchronized void start() throws Exception {
        ModuleState current = state.get();
        if (current == ModuleState.RUNNING || current == ModuleState.STARTING) {
            return;
        }
        if (current != ModuleState.CREATED && current != ModuleState.STOPPED) {
            throw new IllegalStateException("Cannot start module " + name + " from " + current);
        }
        state.set(ModuleState.STARTING);
        try {
            onStart();
            state.set(ModuleState.RUNNING);
        } catch (Exception exception) {
            state.set(ModuleState.FAILED);
            try {
                onStop();
            } catch (Exception cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    @Override
    public final synchronized void stop() throws Exception {
        ModuleState current = state.get();
        if (current == ModuleState.STOPPED || current == ModuleState.CREATED) {
            state.set(ModuleState.STOPPED);
            return;
        }
        if (current == ModuleState.STOPPING) {
            return;
        }
        state.set(ModuleState.STOPPING);
        try {
            onStop();
            state.set(ModuleState.STOPPED);
        } catch (Exception exception) {
            state.set(ModuleState.FAILED);
            throw exception;
        }
    }

    protected abstract void onStart() throws Exception;

    protected abstract void onStop() throws Exception;
}
