package com.warsim.frontline.api.objective;

import java.util.HashSet;
import java.util.List;

public record ObjectiveConfiguration(
    boolean enabled,
    int scanIntervalTicks,
    List<ObjectiveDefinition> definitions
) {
    public ObjectiveConfiguration {
        definitions = List.copyOf(definitions);
        if (scanIntervalTicks < 1 || scanIntervalTicks > 20) {
            throw new IllegalArgumentException("scanIntervalTicks must be 1-20");
        }
        if (enabled && definitions.isEmpty()) {
            throw new IllegalArgumentException("At least one objective is required");
        }
        if (definitions.size() > 5) {
            throw new IllegalArgumentException("At most five objectives are supported");
        }
        HashSet<ObjectiveId> ids = new HashSet<>();
        for (ObjectiveDefinition definition : definitions) {
            if (!ids.add(definition.objectiveId())) {
                throw new IllegalArgumentException("Duplicate objective ID");
            }
        }
    }

    public static ObjectiveConfiguration disabled() {
        return new ObjectiveConfiguration(false, 5, List.of());
    }

    public static ObjectiveConfiguration testDefaults(boolean enabled) {
        if (!enabled) return disabled();
        return new ObjectiveConfiguration(true, 5, List.of(new ObjectiveDefinition(
            new ObjectiveId("alpha"), "A点",
            new ObjectiveRegion("world", .5, 64, .5, 8, 6),
            ObjectiveOwner.DEFENDERS, false,
            new ObjectiveCaptureRules(30, 4, .5, EmptyBehavior.RETURN_TO_OWNER,
                ContestedBehavior.FREEZE),
            new ObjectiveRewards(50, 0)
        )));
    }
}
