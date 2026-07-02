package com.warsim.frontline.api.objective;

@FunctionalInterface
public interface ObjectiveEventListener {
    void onEvent(ObjectiveEvent event);
}
