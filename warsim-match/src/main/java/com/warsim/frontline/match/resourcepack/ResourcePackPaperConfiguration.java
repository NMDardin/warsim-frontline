package com.warsim.frontline.match.resourcepack;

import java.util.Objects;
import java.util.regex.Pattern;

public record ResourcePackPaperConfiguration(
    boolean enabled,
    String contentVersion,
    Pack pack,
    Validation validation,
    Send send
) {
    public ResourcePackPaperConfiguration {
        contentVersion = text(contentVersion, "contentVersion");
        pack = Objects.requireNonNull(pack, "pack");
        validation = Objects.requireNonNull(validation, "validation");
        send = Objects.requireNonNull(send, "send");
    }

    public static ResourcePackPaperConfiguration disabled() {
        return new ResourcePackPaperConfiguration(
            false,
            "disabled",
            new Pack("", "", false, ""),
            new Validation(false, false, "warsim", false, false),
            new Send(false, 40, false)
        );
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("resource-pack." + name + " is required");
        }
        return value;
    }

    public record Pack(String url, String sha1, boolean required, String prompt) {
        private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");

        public Pack {
            url = Objects.requireNonNullElse(url, "");
            sha1 = Objects.requireNonNullElse(sha1, "");
            prompt = Objects.requireNonNullElse(prompt, "");
            if (!sha1.isBlank() && !SHA1.matcher(sha1).matches()) {
                throw new IllegalArgumentException("resource-pack.pack.sha1 must be empty or 40 hex characters");
            }
        }

        public boolean urlConfigured() {
            return !url.isBlank();
        }

        public boolean sha1Configured() {
            return !sha1.isBlank();
        }
    }

    public record Validation(
        boolean enabled,
        boolean failClosedOnInvalidManifest,
        String expectedNamespace,
        boolean requireFormalWeaponItems,
        boolean requirePlaceholderModels
    ) {
        public Validation {
            expectedNamespace = text(expectedNamespace, "validation.expected-namespace");
        }
    }

    public record Send(boolean onJoin, int delayTicks, boolean resendOnStatusFailed) {
        public Send {
            if (delayTicks < 0 || delayTicks > 1200) {
                throw new IllegalArgumentException("resource-pack.send.delay-ticks must be 0-1200");
            }
        }
    }
}
