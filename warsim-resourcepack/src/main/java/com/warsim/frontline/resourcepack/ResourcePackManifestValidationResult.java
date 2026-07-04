package com.warsim.frontline.resourcepack;

import java.util.List;
import java.util.Objects;

public record ResourcePackManifestValidationResult(
    boolean valid,
    List<String> errors,
    List<String> warnings,
    int requiredEntries,
    int optionalEntries,
    String contentVersion
) {
    public ResourcePackManifestValidationResult {
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        contentVersion = Objects.requireNonNullElse(contentVersion, "");
    }

    public static ResourcePackManifestValidationResult failure(String message) {
        return new ResourcePackManifestValidationResult(
            false, List.of(message), List.of(), 0, 0, ""
        );
    }
}
