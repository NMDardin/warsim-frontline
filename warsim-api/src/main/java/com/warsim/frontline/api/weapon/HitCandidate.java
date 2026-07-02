package com.warsim.frontline.api.weapon;

import java.util.Objects;
import java.util.UUID;

public record HitCandidate(
    UUID targetUuid,
    UUID matchId,
    String worldName,
    AxisAlignedBox bodyBox,
    AxisAlignedBox headBox
) {
    public HitCandidate {
        Objects.requireNonNull(targetUuid, "targetUuid");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(bodyBox, "bodyBox");
        Objects.requireNonNull(headBox, "headBox");
    }
}
