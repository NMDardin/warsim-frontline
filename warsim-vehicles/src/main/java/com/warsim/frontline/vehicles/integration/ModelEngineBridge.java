package com.warsim.frontline.vehicles.integration;

/**
 * Placeholder boundary for ModelEngine-owned vehicle models, bones, animations and mounts.
 * No ModelEngine API is invoked in T-002.
 */
public interface ModelEngineBridge {
    IntegrationStatus status();
}
