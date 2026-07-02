package com.warsim.frontline.api.roster;

import java.util.Optional;
import java.util.UUID;

public interface CombatAffiliationService {
    CombatRelation relation(UUID first, UUID second);
    default boolean sameTeam(UUID first, UUID second) {
        CombatRelation relation = relation(first, second);
        return relation == CombatRelation.SELF || relation == CombatRelation.SQUADMATE
            || relation == CombatRelation.TEAMMATE;
    }
    default boolean sameSquad(UUID first, UUID second) {
        return relation(first, second) == CombatRelation.SQUADMATE;
    }
    Optional<TeamSide> teamOf(UUID playerUuid);
    Optional<SquadId> squadOf(UUID playerUuid);
}
