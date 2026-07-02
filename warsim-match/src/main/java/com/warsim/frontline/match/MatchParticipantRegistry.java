package com.warsim.frontline.match;

import com.warsim.frontline.api.match.MatchParticipant;
import com.warsim.frontline.api.match.MatchParticipantJoinResult;
import com.warsim.frontline.api.match.MatchParticipantState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

final class MatchParticipantRegistry {
    private final Map<UUID, MatchParticipant> participants = new LinkedHashMap<>();
    private int peak;

    MatchParticipantJoinResult join(
        UUID playerUuid,
        String name,
        UUID matchId,
        Instant joinedAt,
        MatchParticipantState state,
        boolean joinedDuringRound,
        int maximumPlayers
    ) {
        MatchParticipant existing = participants.get(playerUuid);
        if (existing != null) {
            MatchParticipant updated = new MatchParticipant(
                playerUuid, name, matchId, existing.joinedAt(), state, joinedDuringRound
            );
            participants.put(playerUuid, updated);
            return new MatchParticipantJoinResult(true, false, updated, "参与者已更新");
        }
        if (participants.size() >= maximumPlayers) {
            return MatchParticipantJoinResult.rejected("战场人数已满");
        }
        MatchParticipant participant = new MatchParticipant(
            playerUuid, name, matchId, joinedAt, state, joinedDuringRound
        );
        participants.put(playerUuid, participant);
        peak = Math.max(peak, participants.size());
        return new MatchParticipantJoinResult(true, true, participant, "已加入当前对局");
    }

    boolean leave(UUID playerUuid) {
        return participants.remove(playerUuid) != null;
    }

    void activateAll(UUID matchId) {
        participants.replaceAll((uuid, participant) -> new MatchParticipant(
            uuid, participant.currentName(), matchId, participant.joinedAt(),
            MatchParticipantState.ACTIVE, participant.joinedDuringRound()
        ));
    }

    int size() {
        return participants.size();
    }

    Optional<MatchParticipant> find(UUID playerUuid) {
        return Optional.ofNullable(participants.get(playerUuid));
    }

    int peak() {
        return peak;
    }

    void clear() {
        participants.clear();
        peak = 0;
    }
}
