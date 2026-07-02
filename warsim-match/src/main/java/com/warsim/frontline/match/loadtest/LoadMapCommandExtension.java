package com.warsim.frontline.match.loadtest;

import com.warsim.frontline.admin.WarSimCommandExtension;
import com.warsim.frontline.api.loadtest.*;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;

public final class LoadMapCommandExtension implements WarSimCommandExtension {
    private final LoadScenarioService service;

    public LoadMapCommandExtension(LoadScenarioService service) {
        this.service = service;
    }

    @Override public String name() {
        return "loadmap";
    }

    @Override public boolean execute(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("warsim.admin.loadmap")) {
            sender.sendMessage("§c你没有权限查看或准备负载测试地图。");
            return true;
        }
        if (arguments.length < 2) {
            usage(sender);
            return true;
        }
        String action = arguments[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> status(sender);
            case "list" -> list(sender);
            case "validate" -> validate(sender, arguments);
            case "scenarios" -> scenarios(sender);
            case "scenario" -> scenario(sender, arguments);
            case "prepare" -> prepare(sender, arguments);
            case "clean" -> clean(sender);
            case "snapshot" -> snapshot(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        LoadScenarioSnapshot snapshot = service.snapshot();
        sender.sendMessage("§6LoadMap状态");
        sender.sendMessage("§f状态：§a" + snapshot.state());
        sender.sendMessage("§f地图：§a" + (snapshot.activeMapId() == null ? "未加载" : snapshot.activeMapId()));
        sender.sendMessage("§f准备场景：§a" + (snapshot.preparedScenarioId() == null ? "无" : snapshot.preparedScenarioId()));
        sender.sendMessage("§f地图数量：§a" + snapshot.mapsLoaded() + " §f场景数量：§a" + snapshot.scenariosLoaded());
        snapshot.validationMessages().stream().limit(6).forEach(line -> sender.sendMessage("§7- " + line));
    }

    private void list(CommandSender sender) {
        sender.sendMessage("§6LoadMap清单");
        for (LoadMapDefinition map : service.maps()) {
            sender.sendMessage("§f" + map.mapId() + " §7world=" + map.worldName()
                + " version=" + map.version().value());
            sender.sendMessage("§7  " + map.coordinateNotice());
            sender.sendMessage("§7  zones=" + map.zones().size());
        }
    }

    private void validate(CommandSender sender, String[] arguments) {
        LoadMapId mapId;
        try {
            mapId = arguments.length >= 3
                ? new LoadMapId(arguments[2])
                : service.maps().stream().findFirst().map(LoadMapDefinition::mapId).orElse(null);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§cLoadMap ID非法。");
            return;
        }
        if (mapId == null) {
            sender.sendMessage("§c没有可验证的LoadMap。");
            return;
        }
        LoadScenarioValidationResult result = service.validate(mapId);
        sender.sendMessage((result.successful() ? "§a" : "§c") + result.message());
        result.details().stream().limit(20).forEach(line -> sender.sendMessage("§7- " + line));
    }

    private void scenarios(CommandSender sender) {
        sender.sendMessage("§6LoadScenario清单");
        for (LoadScenarioDefinition scenario : service.scenarios()) {
            sender.sendMessage("§f" + scenario.scenarioId() + " §7type=" + scenario.type()
                + " slots=" + scenario.slots().size()
                + " lanes=" + scenario.lanes().size());
        }
    }

    private void scenario(CommandSender sender, String[] arguments) {
        if (arguments.length < 3) {
            sender.sendMessage("§e用法：/warsim loadmap scenario <scenarioId>");
            return;
        }
        try {
            service.scenario(new LoadScenarioId(arguments[2])).ifPresentOrElse(
                scenario -> {
                    sender.sendMessage("§6LoadScenario " + scenario.scenarioId());
                    sender.sendMessage("§f类型：§a" + scenario.type());
                    sender.sendMessage("§f地图：§a" + scenario.mapId() + " §f版本：§a" + scenario.mapVersion().value());
                    sender.sendMessage("§f区域：§a" + String.join(", ", scenario.zoneIds()));
                    sender.sendMessage("§f槽位：§a" + scenario.slots().size() + " §f通道：§a" + scenario.lanes().size());
                    sender.sendMessage("§7" + scenario.description());
                },
                () -> sender.sendMessage("§c未知场景。")
            );
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c场景ID非法。");
        }
    }

    private void prepare(CommandSender sender, String[] arguments) {
        if (arguments.length < 3) {
            sender.sendMessage("§e用法：/warsim loadmap prepare <scenarioId>");
            return;
        }
        try {
            LoadScenarioPreparationResult result =
                service.prepare(new LoadScenarioId(arguments[2]));
            sender.sendMessage((result.successful() ? "§a" : "§c") + result.message());
            status(sender);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c场景ID非法。");
        }
    }

    private void clean(CommandSender sender) {
        LoadScenarioPreparationResult result = service.clean();
        sender.sendMessage((result.successful() ? "§a" : "§c") + result.message());
    }

    private void snapshot(CommandSender sender) {
        LoadScenarioSnapshot snapshot = service.snapshot();
        sender.sendMessage("§6LoadMap Snapshot");
        sender.sendMessage("§fstate=§a" + snapshot.state()
            + " §fmap=§a" + snapshot.activeMapId()
            + " §fscenario=§a" + snapshot.preparedScenarioId());
        sender.sendMessage("§fvalidations=§a" + snapshot.metrics().validations()
            + " §ffailures=§a" + snapshot.metrics().validationFailures()
            + " §fpreparations=§a" + snapshot.metrics().preparations()
            + " §fcleans=§a" + snapshot.metrics().cleans());
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("§e用法：/warsim loadmap <status|list|validate|scenarios|scenario|prepare|clean|snapshot>");
    }

    @Override public List<String> complete(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("warsim.admin.loadmap")) return List.of();
        if (arguments.length == 2) {
            return List.of("status", "list", "validate", "scenarios", "scenario", "prepare", "clean", "snapshot")
                .stream().filter(value -> value.startsWith(arguments[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (arguments.length == 3 && List.of("scenario", "prepare").contains(arguments[1].toLowerCase(Locale.ROOT))) {
            return service.scenarios().stream().map(value -> value.scenarioId().value())
                .filter(value -> value.startsWith(arguments[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (arguments.length == 3 && "validate".equalsIgnoreCase(arguments[1])) {
            return service.maps().stream().map(value -> value.mapId().value())
                .filter(value -> value.startsWith(arguments[2].toLowerCase(Locale.ROOT))).toList();
        }
        return Arrays.asList();
    }
}
