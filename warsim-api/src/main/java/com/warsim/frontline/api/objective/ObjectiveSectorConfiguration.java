package com.warsim.frontline.api.objective;

import java.util.HashSet;
import java.util.List;

public record ObjectiveSectorConfiguration(
    boolean enabled,
    ObjectiveSectorId initialSector,
    SectorCompletionMode completionMode,
    int advanceDelaySeconds,
    boolean attackerVictoryOnFinalSector,
    List<ObjectiveSectorDefinition> definitions
) {
    public ObjectiveSectorConfiguration {
        completionMode = completionMode == null
            ? SectorCompletionMode.ALL_OBJECTIVES_CAPTURED : completionMode;
        definitions = List.copyOf(definitions);
        if (advanceDelaySeconds < 0 || advanceDelaySeconds > 60) {
            throw new IllegalArgumentException("advanceDelaySeconds must be 0-60");
        }
        if (enabled) {
            if (initialSector == null) {
                throw new IllegalArgumentException("initialSector is required when sectors are enabled");
            }
            if (definitions.isEmpty()) {
                throw new IllegalArgumentException("At least one objective sector is required");
            }
            HashSet<ObjectiveSectorId> ids = new HashSet<>();
            HashSet<Integer> orders = new HashSet<>();
            boolean initialFound = false;
            for (ObjectiveSectorDefinition definition : definitions) {
                if (!ids.add(definition.sectorId())) {
                    throw new IllegalArgumentException("Duplicate objective sector ID");
                }
                if (!orders.add(definition.order())) {
                    throw new IllegalArgumentException("Duplicate objective sector order");
                }
                if (definition.sectorId().equals(initialSector)) {
                    initialFound = true;
                }
            }
            if (!initialFound) {
                throw new IllegalArgumentException("initialSector must exist in sector definitions");
            }
        }
    }

    public static ObjectiveSectorConfiguration disabled() {
        return new ObjectiveSectorConfiguration(
            false, null, SectorCompletionMode.ALL_OBJECTIVES_CAPTURED, 0, true, List.of()
        );
    }
}
