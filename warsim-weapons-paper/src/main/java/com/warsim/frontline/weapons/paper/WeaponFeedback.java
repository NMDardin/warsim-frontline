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

    void shot(Player player, ShotResult result) {
        switch (result.outcome()) {
            case FIRED_BODY_HIT -> player.sendActionBar(Component.text("§f命中"));
            case FIRED_HEAD_HIT -> player.sendActionBar(Component.text("§e爆头命中"));
            case FRIENDLY_BLOCKED -> notice(player, "§e友军伤害已阻止");
            case REJECTED_EMPTY -> notice(player, "§c弹匣为空，按Q装填");
            case REJECTED_INTERNAL_ERROR -> notice(player, "§c射击处理失败");
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
}
