package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.admin.WarSimCommandExtension;
import com.warsim.frontline.api.weapon.*;
import com.warsim.frontline.weapons.DefaultWeaponService;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class WeaponCommandExtension implements WarSimCommandExtension {
    private final DefaultWeaponService service;
    private final CraftEngineWeaponGateway gateway;
    private final com.warsim.frontline.api.battle.WarSimBattleRuntime runtime;
    private final WeaponPaperConfiguration configuration;

    WeaponCommandExtension(
        DefaultWeaponService service, CraftEngineWeaponGateway gateway,
        com.warsim.frontline.api.battle.WarSimBattleRuntime runtime,
        WeaponPaperConfiguration configuration
    ) {
        this.service = service;
        this.gateway = gateway;
        this.runtime = runtime;
        this.configuration = configuration;
    }

    @Override public String name() { return "weapon"; }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e用法：/warsim weapon <status|list|give|ammo|refill|clear|inspect>");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!permission(sender, "warsim.admin.weapon." + action)) return true;
        switch (action) {
            case "status" -> status(sender);
            case "list" -> list(sender);
            case "give" -> give(sender, args);
            case "ammo" -> ammo(sender, args);
            case "refill" -> refill(sender, args);
            case "clear" -> clear(sender, args);
            case "inspect" -> inspect(sender, args);
            default -> sender.sendMessage(
                "§e用法：/warsim weapon <status|list|give|ammo|refill|clear|inspect>"
            );
        }
        return true;
    }

    private void status(CommandSender sender) {
        WeaponMetricsSnapshot metrics = service.metrics();
        sender.sendMessage("§6WarSim Weapons状态");
        sender.sendMessage("§f启用：§a" + configuration.core().enabled());
        sender.sendMessage("§f生命周期：§a" + service.state());
        sender.sendMessage("§f当前Match：§a" + runtime.snapshot().matchId());
        sender.sendMessage("§f已配置武器：§a" + metrics.configuredWeapons());
        sender.sendMessage("§f活动状态：§a" + metrics.activeWeaponStates());
        sender.sendMessage("§f射击请求/成功/拒绝：§a" + metrics.shotsRequested()
            + "/" + metrics.shotsFired() + "/" + metrics.shotsRejected());
        sender.sendMessage("§f命中 body/head/友军阻止：§a" + metrics.bodyHits()
            + "/" + metrics.headHits() + "/" + metrics.friendlyHitsBlocked());
        sender.sendMessage("§f最近/最大处理耗时(ns)：§a"
            + metrics.lastShotProcessingNanos() + "/" + metrics.maximumShotProcessingNanos());
        if (configuration.error() != null) {
            sender.sendMessage("§f配置错误：§c" + configuration.error());
        }
        List<String> unavailable = gateway.unavailableBindings();
        sender.sendMessage("§fCraftEngine绑定：§a"
            + (unavailable.isEmpty() ? "正常" : "缺失 " + unavailable));
    }

    private void list(CommandSender sender) {
        sender.sendMessage("§6WarSim 测试武器");
        service.definitions().forEach(definition -> sender.sendMessage(
            "§f" + definition.weaponId() + " §7- §a" + definition.displayName()
                + " §7(" + definition.craftEngineItemId() + ")"
        ));
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("§e用法：/warsim weapon give <玩家> <weaponId>");
            return;
        }
        Player player = exact(sender, args[1]);
        if (player == null) return;
        WeaponId id;
        try {
            id = new WeaponId(args[2].toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c武器ID非法。");
            return;
        }
        Optional<ItemStack> item = gateway.create(id, player);
        if (item.isEmpty()) {
            sender.sendMessage("§cCraftEngine物品未加载或武器ID未知。");
            return;
        }
        if (!player.getInventory().addItem(item.get()).isEmpty()) {
            sender.sendMessage("§c玩家背包已满，未丢弃物品。");
            return;
        }
        var battle = runtime.snapshot();
        if (battle.available()) service.refill(player.getUniqueId(), battle.matchId(), id);
        sender.sendMessage("§a测试武器已发放。");
    }

    private void ammo(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("§e用法：/warsim weapon ammo <玩家>");
            return;
        }
        Player player = exact(sender, args[1]);
        if (player == null) return;
        var battle = runtime.snapshot();
        for (WeaponDefinition definition : service.definitions()) {
            service.runtimeState(player.getUniqueId(), battle.matchId(), definition.weaponId())
                .ifPresent(state -> sender.sendMessage("§f" + definition.weaponId()
                    + "：§a" + state.magazineAmmo() + "/" + state.reserveAmmo()
                    + " " + state.reloadState()));
        }
    }

    private void refill(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage("§e用法：/warsim weapon refill <玩家> [weaponId]");
            return;
        }
        Player player = exact(sender, args[1]);
        if (player == null) return;
        var battle = runtime.snapshot();
        List<WeaponDefinition> selected = args.length == 3
            ? service.definition(new WeaponId(args[2].toLowerCase(Locale.ROOT))).stream().toList()
            : service.definitions();
        selected.forEach(definition ->
            service.refill(player.getUniqueId(), battle.matchId(), definition.weaponId()));
        sender.sendMessage("§a弹药已补满。");
    }

    private void clear(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("§e用法：/warsim weapon clear <玩家>");
            return;
        }
        Player player = exact(sender, args[1]);
        if (player == null) return;
        for (ItemStack item : player.getInventory().getContents()) {
            if (gateway.identify(item).isPresent()) player.getInventory().removeItem(item);
        }
        service.clearPlayer(player.getUniqueId());
        sender.sendMessage("§aWarSim测试武器及状态已清理。");
    }

    private void inspect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令只能由玩家执行。");
            return;
        }
        gateway.identify(player.getInventory().getItemInMainHand()).ifPresentOrElse(
            id -> sender.sendMessage("§a主手CraftEngine武器："
                + id + "，格式由CraftEngine 26.6.3验证。"),
            () -> sender.sendMessage("§e主手不是已映射的WarSim测试武器。")
        );
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("status", "list", "give", "ammo", "refill", "clear", "inspect")
                .stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .toList();
        }
        if (args.length == 3 && ("give".equalsIgnoreCase(args[0])
            || "refill".equalsIgnoreCase(args[0]))) {
            return service.definitions().stream().map(value -> value.weaponId().value())
                .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    private static boolean permission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage("§c你没有权限执行该命令。");
        return false;
    }

    private static Player exact(CommandSender sender, String name) {
        List<? extends Player> matches = Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.getName().equalsIgnoreCase(name)).toList();
        if (matches.size() != 1) {
            sender.sendMessage("§c未找到唯一匹配的在线玩家。");
            return null;
        }
        return matches.getFirst();
    }
}
