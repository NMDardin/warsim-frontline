package com.warsim.frontline.api.classes;

import java.util.Objects;
import java.util.regex.Pattern;

public record CraftEngineEquipmentReference(String namespacedItemId) implements EquipmentReference {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]{1,96}");

    public CraftEngineEquipmentReference {
        Objects.requireNonNull(namespacedItemId, "namespacedItemId");
        if (!VALID.matcher(namespacedItemId).matches()) {
            throw new IllegalArgumentException("Invalid CraftEngine item id");
        }
    }

    @Override
    public boolean empty() {
        return false;
    }
}
