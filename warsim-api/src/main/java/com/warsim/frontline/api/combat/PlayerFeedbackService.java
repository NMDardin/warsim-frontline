package com.warsim.frontline.api.combat;

import java.util.UUID;

public interface PlayerFeedbackService {
    boolean submit(FeedbackMessage message);

    void clear(UUID playerUuid);

    void clearMatch(UUID matchId);
}
