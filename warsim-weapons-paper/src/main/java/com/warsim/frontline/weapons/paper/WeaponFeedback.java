package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.weapon.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class WeaponFeedback implements AutoCloseable {
    private final Map<UUID, String> actionBars = new HashMap<>();
    private final Map<UUID, Long> notices = new HashMap<>();

    void show(Player player, ShotFeedbackPresentation presentation) {
        if (presentation.delivery() == FeedbackDelivery.ACTION_BAR) {
            player.sendActionBar(Component.text(presentation.text()));
        } else {
            notice(player, presentation.text());
        }
    }

    void notice(Player player, String text) {
        long now = System.nanoTime();
        long previous = notices.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < 1_000_000_000L) return;
        notices.put(player.getUniqueId(), now);
        player.sendMessage(text);
    }

    void update(Player player, WeaponDefinition definition, WeaponRuntimeState state) {
        String text = definition.displayName() + "  " + state.magazineAmmo()
            + " / " + state.reserveAmmo()
            + (state.reloadState() == ReloadState.RELOADING ? "  RELOADING" : "");
        if (text.equals(actionBars.put(player.getUniqueId(), text))) return;
        player.sendActionBar(Component.text(text));
    }

    void clear(UUID playerUuid) {
        if (actionBars.remove(playerUuid) == null) return;
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) player.sendActionBar(Component.empty());
        notices.remove(playerUuid);
    }

    void clearAll() {
        for (UUID uuid : java.util.List.copyOf(actionBars.keySet())) clear(uuid);
        notices.clear();
    }

    @Override public void close() {
        clearAll();
    }

    enum FeedbackDelivery { ACTION_BAR, NOTICE }

    record ShotFeedbackPresentation(String text, String deduplicationKey, FeedbackDelivery delivery) {}
}
