package com.warsim.frontline.match;

import com.warsim.frontline.api.match.MatchEvent;
import com.warsim.frontline.api.match.MatchEventListener;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class MatchEventDispatcher {
    private final CopyOnWriteArrayList<Registration> listeners = new CopyOnWriteArrayList<>();
    private final Consumer<RuntimeException> failureHandler;

    MatchEventDispatcher(Consumer<RuntimeException> failureHandler) {
        this.failureHandler = failureHandler;
    }

    AutoCloseable subscribe(MatchEventListener listener, boolean matchScoped) {
        Registration registration = new Registration(listener, matchScoped);
        listeners.add(registration);
        return () -> listeners.remove(registration);
    }

    void publish(MatchEvent event) {
        for (Registration registration : listeners) {
            try {
                registration.listener().onEvent(event);
            } catch (RuntimeException exception) {
                failureHandler.accept(exception);
            }
        }
    }

    void clearMatchScoped() {
        listeners.removeIf(Registration::matchScoped);
    }

    void clear() {
        listeners.clear();
    }

    private record Registration(MatchEventListener listener, boolean matchScoped) {
    }
}
