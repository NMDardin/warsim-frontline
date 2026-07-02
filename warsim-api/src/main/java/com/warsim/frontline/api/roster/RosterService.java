package com.warsim.frontline.api.roster;

import com.warsim.frontline.api.match.MatchState;
import java.time.Instant;
import java.util.UUID;

public interface RosterService extends TeamService, SquadService, CombatAffiliationService, AutoCloseable {
    RosterAdmissionPreparation prepareAdmission(
        UUID playerUuid, String currentName, Instant now, MatchState matchState
    );
    RosterOperationResult commitAdmission(RosterAdmissionPlan plan);
    RosterOperationResult admit(UUID playerUuid, String currentName, Instant now, MatchState matchState);
    RosterOperationResult disconnect(UUID playerUuid, Instant now);
    boolean rollbackAdmission(UUID playerUuid);
    int cleanupExpired(Instant now);
    void beginMatch(UUID matchId);
    void clear();
    RosterSnapshot snapshot();
    RosterMetricsSnapshot metrics();
    RosterInvariantReport checkInvariants();
    @Override void close();
}
