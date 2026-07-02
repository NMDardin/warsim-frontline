package com.warsim.frontline.match;

import com.warsim.frontline.admin.WarSimCommand;
import com.warsim.frontline.admin.WarSimCommandRegistry;
import com.warsim.frontline.api.battle.WarSimBattleRuntime;
import com.warsim.frontline.api.node.NodeIds;
import com.warsim.frontline.api.node.NodeType;
import com.warsim.frontline.api.roster.SquadId;
import com.warsim.frontline.api.roster.TeamSide;
import com.warsim.frontline.api.objective.ObjectiveId;
import com.warsim.frontline.api.objective.ObjectiveOwner;
import com.warsim.frontline.api.ticket.TicketOperationType;
import com.warsim.frontline.api.performance.PerformanceService;
import com.warsim.frontline.match.config.PaperConfigLoader;
import com.warsim.frontline.match.config.WarSimPaperConfig;
import com.warsim.frontline.match.combat.CombatOutcomeCoordinator;
import com.warsim.frontline.match.database.PaperDatabaseCoordinator;
import com.warsim.frontline.match.paper.PaperMatchCoordinator;
import com.warsim.frontline.match.paper.PaperBattleRuntime;
import com.warsim.frontline.match.paper.PaperClassCoordinator;
import com.warsim.frontline.match.loadtest.DefaultLoadScenarioService;
import com.warsim.frontline.match.loadtest.LoadMapCommandExtension;
import com.warsim.frontline.match.performance.DefaultPerformanceService;
import com.warsim.frontline.match.performance.PerformanceCommandExtension;
import com.warsim.frontline.match.performance.PerformanceConfiguration;
import com.warsim.frontline.match.redis.PaperNodePublication;
import com.warsim.frontline.match.session.LocalSessionRegistry;
import com.warsim.frontline.match.redis.PaperRedisCoordinator;
import com.warsim.frontline.network.MessageCodec;
import com.warsim.frontline.network.NetworkMessage;
import com.warsim.frontline.network.ProtocolException;
import com.warsim.frontline.network.ProtocolVersion;
import com.warsim.frontline.network.TransferAccepted;
import com.warsim.frontline.network.TransferRejected;
import com.warsim.frontline.network.TransferRequest;
import com.warsim.frontline.network.transfer.TransferRequestRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

public final class WarSimPaperPlugin extends JavaPlugin implements
    Listener,
    PluginMessageListener,
    WarSimCommand.CommandView {

    private static final List<String> MODULES = List.of(
        "api", "common", "network", "database", "resourcepack", "match", "squad",
        "classes", "weapons", "vehicles", "destruction", "progression", "cosmetics",
        "rental", "anticheat", "admin"
    );

    private final MessageCodec codec = new MessageCodec();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private ExecutorService configExecutor;
    private WarSimPaperConfig config;
    private TransferRequestRegistry requestRegistry;
    private LocalSessionRegistry sessionRegistry;
    private PaperDatabaseCoordinator databaseCoordinator;
    private PaperRedisCoordinator redisCoordinator;
    private PaperMatchCoordinator matchCoordinator;
    private PaperClassCoordinator classCoordinator;
    private CombatOutcomeCoordinator combatCoordinator;
    private PaperBattleRuntime battleRuntime;
    private WarSimCommandRegistry commandRegistry;
    private DefaultPerformanceService performanceService;
    private DefaultLoadScenarioService loadScenarioService;
    private boolean messagingRegistered;

    @Override
    public void onEnable() {
        commandRegistry = new WarSimCommandRegistry();
        battleRuntime = new PaperBattleRuntime(exception -> getLogger().log(
            Level.WARNING,
            "[warsim-match] Battle Runtime监听器执行失败。",
            exception
        ));
        getServer().getServicesManager().register(
            WarSimBattleRuntime.class, battleRuntime, this, ServicePriority.Normal
        );
        getServer().getServicesManager().register(
            WarSimCommandRegistry.class, commandRegistry, this, ServicePriority.Normal
        );
        performanceService = new DefaultPerformanceService(
            this,
            PerformanceConfiguration.disabled(getDataFolder().toPath().resolve("performance-reports")),
            "initializing"
        );
        getServer().getServicesManager().register(
            PerformanceService.class, performanceService, this, ServicePriority.Normal
        );
        getLogger().info("[warsim-match] 正在异步加载配置并初始化 WarSim: Frontline。");
        configExecutor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("warsim-config-loader").factory()
        );
        CompletableFuture
            .supplyAsync(() -> PaperConfigLoader.load(this, getLogger()), configExecutor)
            .whenComplete((loaded, failure) -> {
                if (failure != null) {
                    getLogger().log(Level.SEVERE, "[warsim-match] 配置加载任务异常。", failure);
                    loaded = WarSimPaperConfig.safeDefaults();
                }
                WarSimPaperConfig completedConfig = loaded;
                if (!shuttingDown.get()) {
                    Bukkit.getScheduler().runTask(this, () -> initialize(completedConfig));
                }
            });
    }

    private void initialize(WarSimPaperConfig loadedConfig) {
        if (shuttingDown.get()) {
            return;
        }
        config = loadedConfig;
        performanceService.configure(config.performance(), config.node().id());
        commandRegistry.register(new PerformanceCommandExtension(performanceService));
        loadScenarioService = new DefaultLoadScenarioService(
            this,
            config.node().type() == NodeType.OFFICIAL_BATTLE,
            performanceService,
            () -> matchCoordinator == null
                ? com.warsim.frontline.api.match.MatchState.STOPPED
                : matchCoordinator.snapshot().state()
        );
        commandRegistry.register(new LoadMapCommandExtension(loadScenarioService));
        requestRegistry = new TransferRequestRegistry((delayMillis, task) -> {
            long ticks = Math.max(1, (delayMillis + 49) / 50);
            int taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(this, task, ticks);
            return () -> Bukkit.getScheduler().cancelTask(taskId);
        });
        sessionRegistry = new LocalSessionRegistry(Clock.systemUTC(), config.node().id());
        databaseCoordinator = new PaperDatabaseCoordinator(
            this,
            config.database(),
            config.debugLogging()
        );
        databaseCoordinator.start();
        if (config.node().type() == NodeType.OFFICIAL_BATTLE && config.match().enabled()) {
            matchCoordinator = new PaperMatchCoordinator(
                this,
                config.node().id(),
                config.match(),
                config.matchConfigurationError(),
                config.roster(),
                config.rosterConfigurationError(),
                config.objectives(),
                config.objectiveConfigurationError(),
                config.tickets(),
                config.ticketConfigurationError(),
                playerUuid -> sessionRegistry.find(playerUuid).isPresent(),
                battleRuntime,
                performanceService
            );
            matchCoordinator.start();
            classCoordinator = new PaperClassCoordinator(
                this,
                matchCoordinator,
                battleRuntime,
                config.classes(),
                config.classConfigurationError(),
                config.deployment(),
                config.deploymentConfigurationError()
            );
            classCoordinator.start(commandRegistry);
            combatCoordinator = new CombatOutcomeCoordinator(
                this,
                battleRuntime,
                classCoordinator,
                config.combat(),
                config.combatConfigurationError()
            );
            combatCoordinator.start(commandRegistry);
            matchCoordinator.setSpawnProtectionService(combatCoordinator);
        }
        redisCoordinator = new PaperRedisCoordinator(
            this,
            config.redis(),
            config.node(),
            this::nodePublication
        );
        redisCoordinator.start();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("warsim");
        if (command == null) {
            throw new IllegalStateException("Command warsim is missing from plugin.yml");
        }
        WarSimCommand commandHandler = new WarSimCommand(this, commandRegistry);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        if (config.pluginMessagingEnabled()) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, config.channel());
            getServer().getMessenger().registerIncomingPluginChannel(this, config.channel(), this);
            messagingRegistered = true;
        } else {
            getLogger().warning("[warsim-network] Plugin Messaging 已禁用，转服命令不可用。");
        }

        reportIntegration("ModelEngine", config.modelEngineEnabled());
        reportIntegration("MythicMobs", config.mythicMobsEnabled());

        if (config.node().type() == NodeType.OFFICIAL_BATTLE) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                createBattleSession(player);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            databaseCoordinator.playerJoined(
                player.getUniqueId(),
                player.getName(),
                Instant.now()
            );
        }
        getLogger().info(
            "[warsim-match] 启动完成 node=" + config.node().id()
                + " type=" + config.node().type()
                + " channel=" + config.channel()
        );
    }

    private void reportIntegration(String pluginName, boolean enabled) {
        if (!enabled) {
            getLogger().info("[warsim-vehicles] " + pluginName + " 集成已禁用。");
        } else if (getServer().getPluginManager().getPlugin(pluginName) == null) {
            getLogger().warning(
                "[warsim-vehicles] 未检测到 " + pluginName + "；载具适配保持不可用，插件继续启动。"
            );
        } else {
            getLogger().info("[warsim-vehicles] 检测到 " + pluginName + "，T-002 不调用其 API。");
        }
    }

    @Override
    public void onDisable() {
        shuttingDown.set(true);
        if (messagingRegistered && config != null) {
            getServer().getMessenger().unregisterIncomingPluginChannel(this, config.channel(), this);
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, config.channel());
        }
        if (requestRegistry != null) {
            requestRegistry.close();
        }
        if (matchCoordinator != null) {
            matchCoordinator.close();
        }
        if (combatCoordinator != null) {
            combatCoordinator.close();
        }
        if (classCoordinator != null) {
            classCoordinator.close();
        }
        if (battleRuntime != null) {
            battleRuntime.close();
        }
        if (loadScenarioService != null) {
            loadScenarioService.close();
        }
        if (performanceService != null) {
            performanceService.close();
        }
        if (commandRegistry != null) {
            commandRegistry.close();
        }
        getServer().getServicesManager().unregisterAll(this);
        if (sessionRegistry != null) {
            sessionRegistry.close();
        }
        if (databaseCoordinator != null) {
            databaseCoordinator.close();
        }
        if (redisCoordinator != null) {
            redisCoordinator.close();
        }
        if (configExecutor != null) {
            configExecutor.shutdownNow();
        }
        getLogger().info("[warsim-match] WarSim: Frontline 已安全关闭。");
    }

    @Override
    public void onPluginMessageReceived(
        @NotNull String channel,
        @NotNull Player carrier,
        byte @NotNull [] payload
    ) {
        if (config == null || requestRegistry == null || !config.channel().equals(channel)) {
            return;
        }
        NetworkMessage message;
        try {
            message = codec.decode(
                payload,
                new MessageCodec.DecodePolicy(
                    config.maximumMessageBytes(),
                    config.requestTimeoutMillis(),
                    1000,
                    Clock.systemUTC()
                )
            );
        } catch (ProtocolException exception) {
            getLogger().warning("[warsim-network] 丢弃代理响应：" + exception.getMessage());
            return;
        }
        if (!message.playerUuid().equals(carrier.getUniqueId())) {
            getLogger().warning("[warsim-network] 丢弃玩家UUID与载体不一致的代理响应。");
            return;
        }
        var pending = requestRegistry.get(carrier.getUniqueId());
        if (pending.isEmpty()
            || !pending.get().requestId().equals(message.requestId())
            || !pending.get().sourceNodeId().equals(message.sourceNodeId())
            || !pending.get().targetNodeId().equals(message.targetNodeId())) {
            getLogger().warning("[warsim-network] 丢弃无法匹配活动请求的代理响应。");
            return;
        }
        if (message instanceof TransferAccepted) {
            requestRegistry.complete(carrier.getUniqueId(), message.requestId());
            logTransfer(message, "ACCEPTED", null);
        } else if (message instanceof TransferRejected rejected) {
            requestRegistry.complete(carrier.getUniqueId(), message.requestId());
            carrier.sendMessage("§c" + rejected.userMessage());
            logTransfer(message, "REJECTED", rejected.rejectionCode().name());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (config != null && config.node().type() == NodeType.OFFICIAL_BATTLE) {
            createBattleSession(event.getPlayer());
        }
        if (databaseCoordinator != null) {
            Player player = event.getPlayer();
            databaseCoordinator.playerJoined(
                player.getUniqueId(),
                player.getName(),
                Instant.now()
            );
        }
    }

    private void createBattleSession(Player player) {
        sessionRegistry.join(player.getUniqueId());
        if (matchCoordinator != null) {
            matchCoordinator.playerJoined(player);
        }
        if (classCoordinator != null) {
            classCoordinator.playerJoined(player);
        }
        if (config.debugLogging()) {
            getLogger().info(
                "[warsim-match] 本地战场会话已激活 playerUuid=" + player.getUniqueId()
                    + " node=" + config.node().id()
            );
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        if (requestRegistry != null) {
            requestRegistry.remove(playerUuid);
        }
        if (sessionRegistry != null) {
            if (matchCoordinator != null) {
                matchCoordinator.playerLeft(event.getPlayer());
            }
            sessionRegistry.leave(playerUuid);
        }
        if (databaseCoordinator != null) {
            databaseCoordinator.playerQuit(
                playerUuid,
                event.getPlayer().getName(),
                Instant.now()
            );
        }
    }

    @Override
    public List<String> statusLines() {
        if (config == null) {
            return List.of("§eWarSim: Frontline 正在初始化，请稍后重试。");
        }
        List<String> lines = new ArrayList<>(List.of(
            "§6WarSim: Frontline 状态",
            "§f插件版本：§a" + getPluginMeta().getVersion(),
            "§fJava版本：§a" + System.getProperty("java.version"),
            "§f服务端版本：§a" + Bukkit.getVersion(),
            "§f已加载模块：§a" + String.join(", ", MODULES),
            "§f当前节点：§a" + config.node().id()
        ));
        if (databaseCoordinator != null) {
            lines.addAll(databaseCoordinator.statusLines());
        }
        if (redisCoordinator != null) {
            lines.addAll(redisCoordinator.statusLines());
        }
        if (performanceService != null) {
            var perf = performanceService.snapshot(java.util.Optional.empty());
            lines.add("§fPerformance状态：§a" + perf.state());
            lines.add("§fPerformance指标窗口：§a" + perf.serviceMetrics().metricWindows()
                + "/" + perf.serviceMetrics().maximumMetricWindows());
        }
        if (loadScenarioService != null) {
            var loadMap = loadScenarioService.snapshot();
            lines.add("§fLoadMap状态：§a" + loadMap.state());
            lines.add("§fLoadMap准备场景：§a"
                + (loadMap.preparedScenarioId() == null ? "无" : loadMap.preparedScenarioId()));
        }
        if (classCoordinator != null) {
            lines.addAll(classCoordinator.statusLines());
        }
        if (combatCoordinator != null) {
            lines.addAll(combatCoordinator.statusLines());
        }
        lines.addAll(matchStatusLines());
        return List.copyOf(lines);
    }

    @Override
    public List<String> allowedTargets() {
        return config == null ? List.of() : config.sortedAllowedTargets();
    }

    @Override
    public void join(Player player, String targetNodeId) {
        if (config == null || requestRegistry == null) {
            player.sendMessage("§cWarSim 正在初始化，请稍后重试。");
            return;
        }
        if (config.node().type() != NodeType.LOBBY) {
            player.sendMessage("§c当前节点不允许通过该命令加入战场。");
            return;
        }
        if (!NodeIds.isValid(targetNodeId) || !config.allowedTargets().contains(targetNodeId)) {
            player.sendMessage("§c目标战场不存在或不允许加入。");
            return;
        }
        if (!messagingRegistered) {
            player.sendMessage("§c跨服通信频道当前不可用。");
            return;
        }

        UUID requestId = UUID.randomUUID();
        UUID playerUuid = player.getUniqueId();
        boolean registered = requestRegistry.register(
            playerUuid,
            requestId,
            config.node().id(),
            targetNodeId,
            config.requestTimeoutMillis(),
            () -> {
                Player online = Bukkit.getPlayer(playerUuid);
                if (online != null) {
                    online.sendMessage("§c转服请求超时，请稍后重试。");
                }
                getLogger().warning(
                    "[warsim-network] requestId=" + requestId
                        + " playerUuid=" + playerUuid
                        + " sourceNode=" + config.node().id()
                        + " targetNode=" + targetNodeId
                        + " result=TIMEOUT rejectionCode=REQUEST_EXPIRED"
                );
            }
        );
        if (!registered) {
            player.sendMessage("§c你已有一个正在处理的转服请求。");
            return;
        }

        TransferRequest request = new TransferRequest(
            ProtocolVersion.CURRENT,
            requestId,
            playerUuid,
            config.node().id(),
            targetNodeId,
            Clock.systemUTC().millis()
        );
        try {
            player.sendPluginMessage(this, config.channel(), codec.encode(request, config.maximumMessageBytes()));
            player.sendMessage("§e正在连接至 " + targetNodeId + "……");
            logTransfer(request, "SENT", null);
        } catch (ProtocolException | RuntimeException exception) {
            requestRegistry.complete(playerUuid, requestId);
            player.sendMessage("§c无法发送转服请求，请稍后重试。");
            getLogger().log(Level.SEVERE, "[warsim-network] 编码或发送转服请求失败。", exception);
        }
    }

    @Override
    public void redisPing(CommandSender sender, String targetNodeId) {
        if (!NodeIds.isValid(targetNodeId)) {
            sender.sendMessage("§c目标节点ID非法。");
            return;
        }
        if (redisCoordinator == null) {
            sender.sendMessage("§cRedis模块尚未初始化。");
            return;
        }
        redisCoordinator.ping(sender, targetNodeId);
    }

    @Override
    public List<String> matchStatusLines() {
        if (config == null) {
            return List.of("§eMatch模块正在初始化。");
        }
        if (matchCoordinator == null) {
            return List.of("§fMatch状态：§e未启用");
        }
        return matchCoordinator.statusLines();
    }

    @Override
    public void matchStart(CommandSender sender, boolean force) {
        if (matchCoordinator == null) {
            sender.sendMessage("§c当前节点未启用Match模块。");
            return;
        }
        matchCoordinator.startMatch(sender, force);
    }

    @Override
    public void matchEnd(CommandSender sender, String reason) {
        if (matchCoordinator == null) {
            sender.sendMessage("§c当前节点未启用Match模块。");
            return;
        }
        matchCoordinator.endMatch(sender, reason);
    }

    @Override
    public void matchReset(CommandSender sender) {
        if (matchCoordinator == null) {
            sender.sendMessage("§c当前节点未启用Match模块。");
            return;
        }
        matchCoordinator.resetMatch(sender);
    }

    @Override
    public void matchRecover(CommandSender sender) {
        if (matchCoordinator == null) {
            sender.sendMessage("§c当前节点未启用Match模块。");
            return;
        }
        matchCoordinator.recoverMatch(sender);
    }

    @Override
    public void teamStatus(CommandSender sender) {
        if (!requireRoster(sender)) return;
        matchCoordinator.rosterStatusLines().forEach(sender::sendMessage);
    }

    @Override
    public void teamList(CommandSender sender) {
        if (!requireRoster(sender)) return;
        matchCoordinator.teamListLines().forEach(sender::sendMessage);
    }

    @Override
    public void teamPlayer(CommandSender sender, String playerName) {
        if (!requireRoster(sender)) return;
        Player player = exactOnlinePlayer(sender, playerName);
        if (player != null) matchCoordinator.playerRosterLines(player.getUniqueId()).forEach(sender::sendMessage);
    }

    @Override
    public void teamMove(
        CommandSender sender, String playerName, String team, boolean force
    ) {
        if (!requireRoster(sender)) return;
        Player player = exactOnlinePlayer(sender, playerName);
        if (player == null) return;
        TeamSide side;
        try {
            side = TeamSide.valueOf(team.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c阵营必须是 attackers 或 defenders。");
            return;
        }
        matchCoordinator.moveTeam(sender, player.getUniqueId(), side, force);
        matchCoordinator.playerRosterLines(player.getUniqueId()).forEach(player::sendMessage);
    }

    @Override
    public void teamRebalance(CommandSender sender, String playerName) {
        if (!requireRoster(sender)) return;
        Player player = exactOnlinePlayer(sender, playerName);
        if (player != null) matchCoordinator.rebalance(sender, player.getUniqueId());
    }

    @Override
    public void squadStatus(Player player) {
        if (!requireRoster(player)) return;
        matchCoordinator.playerRosterLines(player.getUniqueId()).forEach(player::sendMessage);
    }

    @Override
    public void squadList(CommandSender sender) {
        if (!requireRoster(sender)) return;
        TeamSide side = sender instanceof Player player
            ? matchCoordinator.roster().teamOf(player.getUniqueId()).orElse(null)
            : null;
        matchCoordinator.squadListLines(side).forEach(sender::sendMessage);
    }

    @Override
    public void squadJoin(Player player, String squadId) {
        if (!requireRoster(player)) return;
        SquadId.parse(squadId).ifPresentOrElse(
            id -> matchCoordinator.switchSquad(player, player.getUniqueId(), id, false),
            () -> player.sendMessage("§c未知小队ID或别名。")
        );
    }

    @Override
    public void squadLeave(Player player) {
        if (requireRoster(player)) {
            matchCoordinator.leaveSquad(player, player.getUniqueId(), false);
        }
    }

    @Override
    public void squadLeader(
        CommandSender sender, String playerName, boolean administrator
    ) {
        if (!requireRoster(sender)) return;
        Player target = exactOnlinePlayer(sender, playerName);
        if (target == null) return;
        if (!(sender instanceof Player actor) && !administrator) {
            sender.sendMessage("§c该命令只能由玩家或管理员控制台执行。");
            return;
        }
        UUID actorUuid = sender instanceof Player player ? player.getUniqueId() : new UUID(0, 0);
        matchCoordinator.transferLeader(
            sender, actorUuid, target.getUniqueId(), administrator
        );
    }

    @Override
    public void squadInspect(CommandSender sender, String squadId) {
        if (!requireRoster(sender)) return;
        SquadId.parse(squadId).ifPresentOrElse(
            id -> matchCoordinator.squadListLines(null).stream()
                .filter(line -> line.contains("/" + id + "("))
                .forEach(sender::sendMessage),
            () -> sender.sendMessage("§c未知小队ID或别名。")
        );
    }

    @Override
    public void squadMove(CommandSender sender, String playerName, String squadId) {
        if (!requireRoster(sender)) return;
        Player player = exactOnlinePlayer(sender, playerName);
        if (player == null) return;
        SquadId.parse(squadId).ifPresentOrElse(
            id -> matchCoordinator.switchSquad(sender, player.getUniqueId(), id, true),
            () -> sender.sendMessage("§c未知小队ID或别名。")
        );
    }

    @Override
    public void squadRemove(CommandSender sender, String playerName) {
        if (!requireRoster(sender)) return;
        Player player = exactOnlinePlayer(sender, playerName);
        if (player != null) {
            matchCoordinator.leaveSquad(sender, player.getUniqueId(), true);
        }
    }

    @Override
    public List<String> objectiveList() {
        return matchCoordinator == null
            ? List.of("§fObjective状态：§e未启用")
            : matchCoordinator.objectiveListLines();
    }

    @Override
    public List<String> objectiveStatus(String objectiveId) {
        if (matchCoordinator == null) return List.of("§fObjective状态：§e未启用");
        try {
            return matchCoordinator.objectiveStatusLines(
                objectiveId == null ? null : new ObjectiveId(objectiveId)
            );
        } catch (IllegalArgumentException exception) {
            return List.of("§c据点ID非法。");
        }
    }

    @Override
    public void objectiveLock(CommandSender sender, String objectiveId) {
        parseObjectiveId(sender, objectiveId,
            id -> matchCoordinator.objectiveLock(sender, id));
    }

    @Override
    public void objectiveUnlock(CommandSender sender, String objectiveId) {
        parseObjectiveId(sender, objectiveId,
            id -> matchCoordinator.objectiveUnlock(sender, id));
    }

    @Override
    public void objectiveReset(CommandSender sender, String objectiveId) {
        if (matchCoordinator == null) {
            sender.sendMessage("§c当前节点未启用据点系统。");
        } else if (objectiveId == null) {
            matchCoordinator.objectiveReset(sender, null);
        } else {
            parseObjectiveId(sender, objectiveId,
                id -> matchCoordinator.objectiveReset(sender, id));
        }
    }

    @Override
    public void objectiveSetOwner(
        CommandSender sender, String objectiveId, String ownerName
    ) {
        ObjectiveOwner owner;
        try {
            owner = ObjectiveOwner.valueOf(ownerName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c所有权必须是 neutral、attackers 或 defenders。");
            return;
        }
        parseObjectiveId(sender, objectiveId,
            id -> matchCoordinator.objectiveSetOwner(sender, id, owner));
    }

    @Override
    public List<String> ticketsStatus() {
        return matchCoordinator == null
            ? List.of("§fTicket状态：§e未启用")
            : matchCoordinator.ticketStatusLines();
    }

    @Override
    public void ticketsModify(
        CommandSender sender, String operation, String teamName, String amountText
    ) {
        if (matchCoordinator == null) {
            sender.sendMessage("§c当前节点未启用票数系统。");
            return;
        }
        TeamSide side;
        TicketOperationType type;
        int amount;
        try {
            side = TeamSide.valueOf(teamName.toUpperCase(java.util.Locale.ROOT));
            type = TicketOperationType.valueOf(operation.toUpperCase(java.util.Locale.ROOT));
            amount = Integer.parseInt(amountText);
            if (amount < 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c阵营或非负整数参数非法。");
            return;
        }
        matchCoordinator.ticketOperation(sender, side, type, amount);
    }

    private void parseObjectiveId(
        CommandSender sender, String value, java.util.function.Consumer<ObjectiveId> action
    ) {
        if (matchCoordinator == null) {
            sender.sendMessage("§c当前节点未启用据点系统。");
            return;
        }
        try {
            action.accept(new ObjectiveId(value.toLowerCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c据点ID非法。");
        }
    }

    private boolean requireRoster(CommandSender sender) {
        if (matchCoordinator != null) return true;
        sender.sendMessage("§c当前节点未启用阵营与小队系统。");
        return false;
    }

    private Player exactOnlinePlayer(CommandSender sender, String name) {
        List<? extends Player> matches = Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.getName().equalsIgnoreCase(name))
            .toList();
        if (matches.size() != 1) {
            sender.sendMessage("§c未找到唯一匹配的在线玩家，请输入完整玩家名。");
            return null;
        }
        return matches.getFirst();
    }

    private PaperNodePublication nodePublication() {
        if (matchCoordinator != null) {
            return matchCoordinator.nodePublication();
        }
        if (config != null && config.node().type() == NodeType.OFFICIAL_BATTLE) {
            return new PaperNodePublication(
                com.warsim.frontline.api.ModuleState.STOPPED,
                com.warsim.frontline.api.redis.NodeAvailability.UNAVAILABLE,
                false,
                Math.min(100, Bukkit.getMaxPlayers())
            );
        }
        return null;
    }

    private void logTransfer(NetworkMessage message, String result, String rejectionCode) {
        getLogger().info(
            "[warsim-network] module=paper requestId=" + message.requestId()
                + " playerUuid=" + message.playerUuid()
                + " sourceNode=" + message.sourceNodeId()
                + " targetNode=" + message.targetNodeId()
                + " result=" + result
                + " rejectionCode=" + (rejectionCode == null ? "NONE" : rejectionCode)
        );
    }
}
