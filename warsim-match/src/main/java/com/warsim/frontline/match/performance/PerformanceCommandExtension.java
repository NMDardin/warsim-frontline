package com.warsim.frontline.match.performance;

import com.warsim.frontline.admin.WarSimCommandExtension;
import com.warsim.frontline.api.performance.*;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

public final class PerformanceCommandExtension implements WarSimCommandExtension {
    private final PerformanceService service;

    public PerformanceCommandExtension(PerformanceService service) {
        this.service = service;
    }

    @Override public String name() { return "perf"; }

    @Override
    public boolean execute(CommandSender sender, String[] arguments) {
        if (arguments.length == 0) {
            usage(sender);
            return true;
        }
        String action = arguments[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("warsim.admin.perf." + permission(action))) {
            sender.sendMessage("§c你没有权限执行该命令。");
            return true;
        }
        try {
            switch (action) {
                case "status" -> status(sender);
                case "metrics" -> metrics(sender, arguments);
                case "alerts" -> alerts(sender);
                case "snapshot" -> snapshot(sender);
                case "export" -> export(sender);
                case "scenarios" -> scenarios(sender);
                case "scenario" -> scenario(sender, arguments);
                case "dryrun" -> dryrun(sender, arguments);
                case "synthetic" -> synthetic(sender, arguments);
                case "reset" -> {
                    service.reset();
                    sender.sendMessage("§aPerformance采样状态已重置。");
                }
                default -> usage(sender);
            }
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c参数无效：" + exception.getMessage());
        }
        return true;
    }

    private void status(CommandSender sender) {
        PerformanceSnapshot snapshot = service.snapshot(Optional.empty());
        PerformanceMetricsSnapshot metrics = snapshot.serviceMetrics();
        sender.sendMessage("§6WarSim Performance");
        sender.sendMessage("§f状态：§a" + snapshot.state());
        sender.sendMessage("§f指标窗口：§a" + metrics.metricWindows() + "/" + metrics.maximumMetricWindows());
        sender.sendMessage("§f累计采样：§a" + metrics.totalSamples());
        sender.sendMessage("§f丢弃采样：§a" + metrics.droppedSamples());
        sender.sendMessage("§f最近告警：§a" + snapshot.alerts().size());
        sender.sendMessage("§fSynthetic：§a" + (service.syntheticStatus().isPresent() ? "已有结果" : "无结果"));
        sender.sendMessage("§f说明：当前命令只展示本地采样状态，不代表性能达标。");
    }

    private void metrics(CommandSender sender, String[] arguments) {
        Optional<PerformanceComponent> component = Optional.empty();
        if (arguments.length == 2) {
            component = Optional.of(PerformanceComponent.valueOf(arguments[1].toUpperCase(Locale.ROOT)));
        } else if (arguments.length > 2) {
            sender.sendMessage("§e用法：/warsim perf metrics [component]");
            return;
        }
        for (PerformanceMetricSnapshot metric : service.snapshot(component).metrics()) {
            sender.sendMessage("§f" + metric.metricId() + " §7" + metric.component()
                + " §fsamples=§a" + metric.sampleCount()
                + " §fmean=§a" + text(metric.meanNanos())
                + " §fp95=§a" + text(metric.percentiles().p95Nanos())
                + " §fmax=§a" + text(metric.maximumNanos()));
        }
    }

    private void alerts(CommandSender sender) {
        List<PerformanceAlert> alerts = service.alerts();
        if (alerts.isEmpty()) {
            sender.sendMessage("§a当前没有Performance慢操作告警。");
            return;
        }
        for (PerformanceAlert alert : alerts) {
            sender.sendMessage("§e" + alert.severity() + " §f" + alert.metricId()
                + " duration=" + alert.durationNanos()
                + " threshold=" + alert.thresholdNanos()
                + " match=" + alert.matchId()
                + " revision=" + alert.lifecycleRevision());
        }
    }

    private void snapshot(CommandSender sender) {
        PerformanceSnapshot snapshot = service.snapshot(Optional.empty());
        sender.sendMessage("§6Performance Snapshot");
        sender.sendMessage("§fnodeId：§a" + snapshot.nodeId());
        sender.sendMessage("§fmatchId：§a" + snapshot.matchId());
        sender.sendMessage("§frevision：§a" + snapshot.lifecycleRevision());
        sender.sendMessage("§fmatchState：§a" + snapshot.matchState());
        sender.sendMessage("§fmetrics：§a" + snapshot.metrics().size());
        sender.sendMessage("§floadScenario：§a" + snapshot.loadScenarioReference());
    }

    private void export(CommandSender sender) {
        PerformanceExportResult result = service.exportReport();
        sender.sendMessage((result.successful() ? "§a" : "§c") + result.message());
        result.files().forEach(path -> sender.sendMessage("§f" + path));
    }

    private void scenarios(CommandSender sender) {
        service.syntheticScenarios().forEach(scenario ->
            sender.sendMessage("§f" + scenario.id() + " §7" + scenario.type()
                + " ops≈" + scenario.estimatedOperations())
        );
    }

    private void scenario(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            sender.sendMessage("§e用法：/warsim perf scenario <id>");
            return;
        }
        SyntheticLoadScenario scenario = service.syntheticScenario(arguments[1])
            .orElseThrow(() -> new IllegalArgumentException("未知synthetic场景"));
        sender.sendMessage("§6" + scenario.id());
        sender.sendMessage("§f类型：§a" + scenario.type());
        sender.sendMessage("§fLoadScenario引用：§a" + scenario.loadScenarioId());
        sender.sendMessage("§fwarmup/measurement：§a" + scenario.warmupIterations()
            + "/" + scenario.measurementIterations());
        sender.sendMessage("§f组件：§a" + scenario.components());
    }

    private void dryrun(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            sender.sendMessage("§e用法：/warsim perf dryrun <id>");
            return;
        }
        SyntheticDryRunPlan plan = service.dryRun(arguments[1]);
        sender.sendMessage("§6Synthetic Dry Run");
        sender.sendMessage("§f场景：§a" + plan.scenario().id());
        sender.sendMessage("§f可执行：§a" + plan.executable());
        sender.sendMessage("§f输入规模：§a" + plan.estimatedOperations());
        sender.sendMessage("§fwarmup/measurement：§a" + plan.warmupIterations()
            + "/" + plan.measurementIterations());
        sender.sendMessage("§f组件：§a" + plan.components());
        sender.sendMessage("§7" + plan.message());
    }

    private void synthetic(CommandSender sender, String[] arguments) {
        if (arguments.length < 2) {
            sender.sendMessage("§e用法：/warsim perf synthetic <id> [iterations]|status|cancel");
            return;
        }
        String sub = arguments[1].toLowerCase(Locale.ROOT);
        if ("status".equals(sub)) {
            sender.sendMessage("§fSynthetic状态：§a" + service.state());
            service.syntheticStatus().ifPresentOrElse(
                result -> sender.sendMessage("§f最近结果：§a" + result.scenarioId()
                    + " completed=" + result.completed()
                    + " cancelled=" + result.cancelled()
                    + " samples=" + result.completedMeasurements()),
                () -> sender.sendMessage("§f最近结果：§e无")
            );
            return;
        }
        if ("cancel".equals(sub)) {
            if (!(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage("§cSynthetic取消只能由控制台执行。");
                return;
            }
            sender.sendMessage(service.cancelSynthetic() ? "§a取消请求已提交。" : "§e当前没有运行中的synthetic。");
            return;
        }
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("§cSynthetic执行只能由控制台执行。");
            return;
        }
        int iterations = arguments.length >= 3 ? Integer.parseInt(arguments[2]) : -1;
        boolean started = service.startSynthetic(arguments[1], iterations);
        sender.sendMessage(started
            ? "§aSynthetic任务已提交。"
            : "§cSynthetic任务未启动：可能未启用、正在运行、处于PLAYING或场景无效。");
    }

    private static String permission(String action) {
        return switch (action) {
            case "metrics", "alerts", "snapshot" -> "status";
            case "scenario", "scenarios", "dryrun" -> "scenario";
            case "synthetic" -> "synthetic";
            default -> action;
        };
    }

    private static String text(java.util.OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : "N/A";
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage("§e用法：/warsim perf <status|metrics|alerts|snapshot|export|scenarios|scenario|dryrun|synthetic|reset>");
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (arguments.length == 1) {
            return List.of("status", "metrics", "alerts", "snapshot", "export",
                "scenarios", "scenario", "dryrun", "synthetic", "reset").stream()
                .filter(value -> value.startsWith(arguments[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (arguments.length == 2 && List.of("scenario", "dryrun").contains(arguments[0].toLowerCase(Locale.ROOT))) {
            return service.syntheticScenarios().stream().map(SyntheticLoadScenario::id)
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(arguments[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (arguments.length == 2 && "synthetic".equalsIgnoreCase(arguments[0])) {
            return java.util.stream.Stream.concat(
                java.util.stream.Stream.of("status", "cancel"),
                service.syntheticScenarios().stream().map(SyntheticLoadScenario::id)
            ).filter(value -> value.toLowerCase(Locale.ROOT).startsWith(arguments[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (arguments.length == 2 && "metrics".equalsIgnoreCase(arguments[0])) {
            return Arrays.stream(PerformanceComponent.values()).map(Enum::name)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> value.startsWith(arguments[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
