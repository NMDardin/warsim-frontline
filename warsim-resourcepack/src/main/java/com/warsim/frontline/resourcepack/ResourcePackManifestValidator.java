package com.warsim.frontline.resourcepack;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ResourcePackManifestValidator {
    public static final List<String> FORMAL_WEAPON_ITEMS = List.of(
        "warsim:wrench_m1895_rifle",
        "warsim:wrench_m1911_pistol",
        "warsim:wrench_m1918_smg",
        "warsim:wrench_m1915_lmg",
        "warsim:wrench_m1897_shotgun",
        "warsim:wrench_m1903_marksman"
    );

    private static final Pattern NAMESPACED = Pattern.compile(
        "[a-z0-9_.-]+:[a-z0-9_./-]+"
    );

    private ResourcePackManifestValidator() {
    }

    public static ResourcePackManifestValidationResult validate(
        ResourcePackManifest manifest,
        String expectedNamespace,
        String expectedContentVersion,
        boolean requireFormalWeaponItems
    ) {
        LinkedHashSet<String> errors = new LinkedHashSet<>();
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        if (manifest == null) {
            return ResourcePackManifestValidationResult.failure("Manifest is missing");
        }
        if (!manifest.namespace().equals(expectedNamespace)) {
            errors.add("Manifest namespace " + manifest.namespace()
                + " does not match expected namespace " + expectedNamespace);
        }
        if (!manifest.contentVersion().equals(expectedContentVersion)) {
            warnings.add("Manifest content version " + manifest.contentVersion()
                + " does not match configured version " + expectedContentVersion);
        }
        HashSet<String> itemIds = new HashSet<>();
        HashSet<String> modelIds = new HashSet<>();
        int required = 0;
        int optional = 0;
        for (ResourcePackManifestEntry entry : manifest.entries()) {
            if (entry.required()) required++;
            else optional++;
            validateNamespaced("item id", entry.itemId(), errors);
            validateNamespaced("model id", entry.modelId(), errors);
            if (!itemIds.add(entry.itemId())) {
                errors.add("Duplicate manifest item id: " + entry.itemId());
            }
            if (!modelIds.add(entry.modelId())) {
                errors.add("Duplicate manifest model id: " + entry.modelId());
            }
        }
        if (requireFormalWeaponItems) {
            Set<String> requiredItems = new HashSet<>();
            for (ResourcePackManifestEntry entry : manifest.entries()) {
                if (entry.required()) requiredItems.add(entry.itemId());
            }
            for (String item : FORMAL_WEAPON_ITEMS) {
                if (!requiredItems.contains(item)) {
                    errors.add("Missing required formal weapon item: " + item);
                }
            }
        }
        return new ResourcePackManifestValidationResult(
            errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings),
            required, optional, manifest.contentVersion()
        );
    }

    private static void validateNamespaced(
        String label, String value, LinkedHashSet<String> errors
    ) {
        if (value == null || !NAMESPACED.matcher(value).matches()
            || value.contains("..") || value.contains("\\") || value.contains("//")) {
            errors.add("Invalid " + label + ": " + value);
        }
    }
}
