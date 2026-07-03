package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.roster.CombatRelation;
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

    void shot(Player player, ShotResult result) {
        switch (result.outcome()) {
            case FIRED_BODY_HIT -> player.sendActionBar(Component.text("\u00a7fHit"));
            case FIRED_HEAD_HIT -> player.sendActionBar(Component.text("\u00a7eHeadshot"));
            case FRIENDLY_BLOCKED -> notice(player, blockedMessage(result));
            case REJECTED_EMPTY -> notice(player, "\u00a7cMagazine empty; press Q to reload");
            case REJECTED_INTERNAL_ERROR -> notice(player, "\u00a7cShot processing failed");
            default -> {
            }
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

    private String blockedMessage(ShotResult result) {
        CombatRelation relation = result.relation();
        return switch (relation) {
            case UNKNOWN -> "\u00a7eTarget relation unknown";
            case SELF -> "\u00a7eSelf damage disabled";
            case SQUADMATE, TEAMMATE -> "\u00a7eFriendly fire blocked";
            default -> "\u00a7eDamage blocked";
        };
    }
}
