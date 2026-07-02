package com.warsim.frontline.match;

import com.warsim.frontline.api.match.MatchParticipantJoinResult;
import com.warsim.frontline.api.match.MatchSnapshot;
import com.warsim.frontline.api.roster.RosterOperationResult;
import com.warsim.frontline.squad.DefaultRosterService;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class MatchRosterCoordinator implements AutoCloseable {
    private final DefaultMatchService match;
    private final DefaultRosterService roster;

    public MatchRosterCoordinator(DefaultMatchService match, DefaultRosterService roster) {
        this.match = Objects.requireNonNull(match, "match");
        this.roster = Objects.requireNonNull(roster, "roster");
    }

    public synchronized MatchParticipantJoinResult admit(
        UUID playerUuid, String currentName, Instant now
    ) {
        MatchSnapshot snapshot = match.snapshot();
        if (!roster.snapshot().matchId().equals(snapshot.matchId())) {
            roster.beginMatch(snapshot.matchId());
        }
        var preparation = roster.prepareAdmission(
            playerUuid, currentName, now, snapshot.state()
        );
        if (!preparation.successful()) {
            return MatchParticipantJoinResult.rejected(preparation.message());
        }
        return match.participantJoinedAtomic(
            playerUuid, currentName, now,
            () -> roster.commitAdmission(preparation.plan())
        );
    }

    public synchronized boolean disconnect(UUID playerUuid, Instant now) {
        roster.disconnect(playerUuid, now);
        return match.participantLeft(playerUuid, now);
    }

    public synchronized boolean tick(Instant now) {
        MatchSnapshot snapshot = match.snapshot();
        boolean changed = !roster.snapshot().matchId().equals(snapshot.matchId());
        if (changed) roster.beginMatch(snapshot.matchId());
        roster.cleanupExpired(now);
        return changed;
    }

    public DefaultRosterService roster() {
        return roster;
    }

    @Override
    public synchronized void close() {
        roster.close();
        match.close();
    }
}
