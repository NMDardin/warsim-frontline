package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.admin.WarSimCommandExtension;
import com.warsim.frontline.api.weapon.WeaponDefinition;
import com.warsim.frontline.api.weapon.WeaponId;
import com.warsim.frontline.api.weapon.WeaponMetricsSnapshot;
import com.warsim.frontline.weapons.DefaultWeaponService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
        DefaultWeaponService service,
        CraftEngineWeaponGateway gateway,
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
            usage(sender);
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
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("§e用法：/warsim weapon <status|list|give|ammo|refill|clear|inspect>");
    }

    private void status(CommandSender sender) {
        WeaponMetricsSnapshot metrics = service.metrics();
        sender.sendMessage("§6WarSim Weapons 状态");
        sender.sendMessage("§f启用：§a" + configuration.core().enabled());
        sender.sendMessage("§f生命周期：§a" + service.state());
        sender.sendMessage("§f当前 Match：§a" + runtime.snapshot().matchId());
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
        sender.sendMessage("§fCraftEngine 绑定：§a"
            + (unavailable.isEmpty() ? "正常" : "缺失 " + unavailable));
    }

    private void list(CommandSender sender) {
        sender.sendMessage("§6WarSim 正式武器目录");
        service.definitions().forEach(definition -> sender.sendMessage(summary(definition)));
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("§e用法：/warsim weapon give <玩家> <weaponId>");
            return;
        }
        Player player = exact(sender, args[1]);
        if (player == null) return;
        WeaponId id = parseWeaponId(sender, args[2]);
        if (id == null) return;
        Optional<ItemStack> item = gateway.create(id, player);
        if (item.isEmpty()) {
            sender.sendMessage("§cCraftEngine 物品未加载或武器 ID 未知。");
            return;
        }
        if (!player.getInventory().addItem(item.get()).isEmpty()) {
            sender.sendMessage("§c玩家背包已满，未丢弃物品。");
            return;
        }
        var battle = runtime.snapshot();
        if (battle.available()) service.refill(player.getUniqueId(), battle.matchId(), id);
        sender.sendMessage("§aWarSim 武器已发放。");
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
        List<WeaponDefinition> selected;
        if (args.length == 3) {
            WeaponId id = parseWeaponId(sender, args[2]);
            if (id == null) return;
            selected = service.definition(id).stream().toList();
            if (selected.isEmpty()) {
                sender.sendMessage("§c未知武器 ID：" + id.value());
                return;
            }
        } else {
            selected = service.definitions();
        }
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
        sender.sendMessage("§aWarSim 武器和运行状态已清理。");
    }

    private void inspect(CommandSender sender, String[] args) {
        if (args.length == 2) {
            WeaponId id = parseWeaponId(sender, args[1]);
            if (id == null) return;
            service.definition(id).ifPresentOrElse(
                definition -> inspectDefinition(sender, definition),
                () -> sender.sendMessage("§c未知武器 ID：" + id.value())
            );
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c控制台检查配置时请使用：/warsim weapon inspect <weaponId>");
            return;
        }
        gateway.identify(player.getInventory().getItemInMainHand()).ifPresentOrElse(
            id -> sender.sendMessage("§a主手 CraftEngine 武器：" + id),
            () -> sender.sendMessage("§e主手不是已映射的 WarSim 武器。")
        );
    }

    private void inspectDefinition(CommandSender sender, WeaponDefinition definition) {
        sender.sendMessage("§6WarSim Weapon");
        sender.sendMessage("§fID：§a" + definition.weaponId());
        sender.sendMessage("§f名称：§a" + definition.displayName());
        sender.sendMessage("§f类型：§a" + definition.category());
        sender.sendMessage("§fCraftEngine：§a" + definition.craftEngineItemId());
        sender.sendMessage("§f弹匣/备弹：§a" + definition.ammo().magazineSize()
            + "/" + definition.ammo().reserveAmmo());
        sender.sendMessage("§f换弹(ms)：§a" + definition.ammo().reloadMillis());
        sender.sendMessage("§fRPM/射程/散布：§a" + definition.roundsPerMinute()
            + "/" + definition.maximumRange() + "/" + definition.accuracy().hipSpreadDegrees());
        sender.sendMessage("§f爆头倍率：§a" + definition.damage().headMultiplier());
        sender.sendMessage("§f伤害曲线：§a" + definition.damage().points());
        sender.sendMessage("§fEnabled：§atrue");
    }

    private static String summary(WeaponDefinition definition) {
        var points = definition.damage().points();
        double firstDamage = points.getFirst().damage();
        double lastDamage = points.getLast().damage();
        return "§f" + definition.weaponId() + " §7- §a" + definition.displayName()
            + " §7type=" + definition.category()
            + " mag=" + definition.ammo().magazineSize()
            + "/" + definition.ammo().reserveAmmo()
            + " damage=" + firstDamage + "->" + lastDamage
            + " item=" + definition.craftEngineItemId()
            + " enabled=true";
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("status", "list", "give", "ammo", "refill", "clear", "inspect")
                .stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .toList();
        }
        if ((args.length == 2 && "inspect".equalsIgnoreCase(args[0]))
            || (args.length == 3 && ("give".equalsIgnoreCase(args[0])
                || "refill".equalsIgnoreCase(args[0])))) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return service.definitions().stream().map(value -> value.weaponId().value())
                .filter(value -> value.startsWith(prefix)).toList();
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

    private static WeaponId parseWeaponId(CommandSender sender, String raw) {
        try {
            return new WeaponId(raw.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c武器 ID 非法。");
            return null;
        }
    }
}
