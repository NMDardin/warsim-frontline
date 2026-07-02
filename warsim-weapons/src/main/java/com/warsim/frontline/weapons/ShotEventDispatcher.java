package com.warsim.frontline.weapons;

import com.warsim.frontline.api.weapon.WeaponEvent;
import com.warsim.frontline.api.weapon.WeaponEventListener;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class ShotEventDispatcher implements AutoCloseable {
    private final CopyOnWriteArrayList<WeaponEventListener> listeners =
        new CopyOnWriteArrayList<>();
    private final Consumer<RuntimeException> failureHandler;

    public ShotEventDispatcher(Consumer<RuntimeException> failureHandler) {
        this.failureHandler = failureHandler;
    }

    public AutoCloseable subscribe(WeaponEventListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void publish(WeaponEvent event) {
        for (WeaponEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                failureHandler.accept(exception);
            }
        }
    }

    @Override public void close() {
        listeners.clear();
    }
}
