package com.warsim.frontline.api.loadtest;

import java.util.List;
import java.util.Objects;

/** Immutable load-test map definition. */
public record LoadMapDefinition(
    LoadMapId mapId,
    LoadMapVersion version,
    String worldName,
    String coordinateNotice,
    List<LoadZoneDefinition> zones
) {
    public LoadMapDefinition {
        Objects.requireNonNull(mapId, "mapId");
        Objects.requireNonNull(version, "version");
        zones = List.copyOf(Objects.requireNonNull(zones, "zones"));
        if (worldName == null || worldName.isBlank() || worldName.length() > 64
            || coordinateNotice == null || coordinateNotice.isBlank()) {
            throw new IllegalArgumentException("Invalid load map definition");
        }
    }
}
