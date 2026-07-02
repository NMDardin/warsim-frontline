package com.warsim.frontline.api.combat;

import java.util.List;
import java.util.UUID;

public record HudSnapshot(UUID playerUuid, HudOwnershipState ownershipState, List<HudLine> lines) {
    public HudSnapshot {
        lines = List.copyOf(lines == null ? List.of() : lines);
    }
}
