package com.warsim.frontline.common.lifecycle;

import com.warsim.frontline.api.WarSimModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ModuleRegistry implements AutoCloseable {
    private final List<WarSimModule> modules = new ArrayList<>();

    public synchronized void register(WarSimModule module) {
        modules.add(Objects.requireNonNull(module, "module"));
    }

    public synchronized List<String> names() {
        return modules.stream().map(WarSimModule::name).toList();
    }

    public synchronized void startAll() throws Exception {
        List<WarSimModule> started = new ArrayList<>();
        try {
            for (WarSimModule module : modules) {
                module.start();
                started.add(module);
            }
        } catch (Exception failure) {
            Collections.reverse(started);
            for (WarSimModule module : started) {
                try {
                    module.stop();
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public synchronized void close() throws Exception {
        Exception failure = null;
        List<WarSimModule> reversed = new ArrayList<>(modules);
        Collections.reverse(reversed);
        for (WarSimModule module : reversed) {
            try {
                module.stop();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
