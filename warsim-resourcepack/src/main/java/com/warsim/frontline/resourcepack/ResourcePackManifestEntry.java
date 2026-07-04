package com.warsim.frontline.resourcepack;

import java.util.Objects;

public record ResourcePackManifestEntry(
    String itemId,
    String modelId,
    String source,
    boolean required
) {
    public ResourcePackManifestEntry {
        itemId = requireText(itemId, "itemId");
        modelId = requireText(modelId, "modelId");
        source = Objects.requireNonNullElse(source, "");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
