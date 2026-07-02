package com.warsim.frontline.api.classes;

@FunctionalInterface
public interface ClassDeploymentEventListener {
    void onEvent(ClassDeploymentEvent event);
}
