package com.warsim.frontline.api.objective;

@FunctionalInterface
public interface ObjectiveSectorEventListener {
    void onEvent(ObjectiveSectorEvent event);
}
