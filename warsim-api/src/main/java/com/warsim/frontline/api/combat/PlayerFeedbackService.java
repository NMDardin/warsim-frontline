package com.warsim.frontline.api.combat;

import java.util.UUID;

public interface PlayerFeedbackService {
    boolean submit(FeedbackMessage message);

    void clear(UUID playerUuid);

    void clear(UUID playerUuid, FeedbackChannel channel);

    void clearMatch(UUID matchId);
}
