package com.warsim.frontline.resourcepack;

import java.util.List;
import java.util.Objects;

public record ResourcePackManifest(
    String id,
    String contentVersion,
    String minecraftTarget,
    String namespace,
    String packFormatNote,
    List<ResourcePackManifestEntry> entries
) {
    public ResourcePackManifest {
        id = requireText(id, "id");
        contentVersion = requireText(contentVersion, "contentVersion");
        minecraftTarget = Objects.requireNonNullElse(minecraftTarget, "");
        namespace = requireText(namespace, "namespace");
        packFormatNote = Objects.requireNonNullElse(packFormatNote, "");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
