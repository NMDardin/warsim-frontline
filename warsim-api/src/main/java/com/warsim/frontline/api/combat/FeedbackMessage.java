package com.warsim.frontline.api.combat;

import java.util.OptionalLong;
import java.util.UUID;

public record FeedbackMessage(
    UUID playerUuid,
    UUID matchId,
    FeedbackChannel channel,
    FeedbackPriority priority,
    String content,
    String deduplicationKey,
    long createdAtMonotonic,
    long expiresAtMonotonic,
    OptionalLong lifeRevision
) {
    public FeedbackMessage {
        if (playerUuid == null || matchId == null || channel == null || priority == null) {
            throw new IllegalArgumentException("Feedback identity is required");
        }
        content = sanitize(content, 96);
        deduplicationKey = sanitize(deduplicationKey == null ? channel.name() : deduplicationKey, 64);
        lifeRevision = lifeRevision == null ? OptionalLong.empty() : lifeRevision;
    }

    private static String sanitize(String value, int maximum) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\p{Cntrl}\\n\\r]", "").strip();
        return cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum);
    }
}
