package com.warsim.frontline.match.paper;

import com.warsim.frontline.api.match.MatchConfiguration;
import com.warsim.frontline.api.match.MatchEndReason;
import com.warsim.frontline.api.match.MatchMetricsSnapshot;
import com.warsim.frontline.api.match.MatchOperationResult;
import com.warsim.frontline.api.match.MatchSnapshot;
import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.match.MatchResetStartedEvent;
import com.warsim.frontline.api.match.MatchStateChangedEvent;
import com.warsim.frontline.api.match.MatchResetContext;
import com.warsim.frontline.api.match.MatchResetResult;
import com.warsim.frontline.api.match.MatchParticipantState;
import com.warsim.frontline.api.roster.*;
import com.warsim.frontline.api.objective.*;
import com.warsim.frontline.api.ticket.*;
import com.warsim.frontline.api.battle.*;
import com.warsim.frontline.api.classes.CombatEligibilityService;
import com.warsim.frontline.api.classes.PlayerCombatState;
import com.warsim.frontline.api.combat.SpawnProtectionRemovalReason;
import com.warsim.frontline.api.combat.SpawnProtectionService;
import com.warsim.frontline.api.performance.*;
import com.warsim.frontline.match.DefaultMatchService;
import com.warsim.frontline.match.MatchRosterCoordinator;
import com.warsim.frontline.match.objective.ObjectiveMatchCoordinator;
import com.warsim.frontline.match.objective.DefaultObjectiveService;
import com.warsim.frontline.match.MatchNodeStatusMapper;
import com.warsim.frontline.match.config.RoundResetPaperConfiguration;
import com.warsim.frontline.match.redis.PaperNodePublication;
import com.warsim.frontline.match.reset.PaperMatchResetService;
import com.warsim.frontline.squad.DefaultRosterService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperMatchCoordinator implements AutoCloseable {
    private final JavaPlugin plugin;
    private final MatchConfiguration configuration;
    private final DefaultMatchService service;
    private final PaperMatchResetService resetService;
    private final DefaultRosterService roster;
    private final MatchRosterCoordinator rosterCoordinator;
    private final PaperMatchPresentation presentation;
    private final Predicate<UUID> localSessionActive;
    private final PaperBattleRuntime battleRuntime;
    private final PerformanceService performanceService;
    private CombatEligibilityService combatEligibilityService;
    private SpawnProtectionService spawnProtectionService;
    private final ObjectiveConfiguration objectiveConfiguration;
    private final String objectiveConfigurationError;
    private final String ticketConfigurationError;
    private final ObjectiveMatchCoordinator objectiveCoordinator;
    private final PaperObjectiveDisplay objectiveDisplay;
    private final Supplier<List<String>> destructionStatusLines;
    private final ArrayDeque<String> administratorHistory = new ArrayDeque<>();
    private BattleRuntimeSnapshot lastPublishedBattleSnapshot;
    private int tickTaskId = -1;
    private long tickCounter;

    public PaperMatchCoordinator(
        JavaPlugin plugin,
        String nodeId,
        MatchConfiguration configuration,
        String configurationError,
        RoundResetPaperConfiguration roundResetConfiguration,
        String roundResetConfigurationError,
        RosterConfiguration rosterConfiguration,
        String rosterConfigurationError,
        ObjectiveConfiguration objectiveConfiguration,
        String objectiveConfigurationError,
        TicketConfiguration ticketConfiguration,
        String ticketConfigurationError,
        String destructionConfigurationError,
        List<Function<MatchResetContext, MatchResetResult>> resetPhaseCallbacks,
        Supplier<List<String>> destructionStatusLines,
        Predicate<UUID> localSessionActive,
        PaperBattleRuntime battleRuntime,
        PerformanceService performanceService
    ) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.localSessionActive = localSessionActive;
        this.battleRuntime = battleRuntime;
        this.performanceService = performanceService;
        this.objectiveConfiguration = objectiveConfiguration;
        this.objectiveConfigurationError = objectiveConfigurationError;
        this.ticketConfigurationError = ticketConfigurationError;
        this.destructionStatusLines = java.util.Objects.requireNonNull(
            destructionStatusLines, "destructionStatusLines"
        );
        AtomicReference<DefaultMatchService> serviceRef = new AtomicReference<>();
        this.resetService = new PaperMatchResetService(
            plugin,
            roundResetConfiguration,
            localSessionActive,
            () -> {
                DefaultMatchService current = serviceRef.get();
                return current == null ? null : current.snapshot();
            },
            resetPhaseCallbacks
        );
        this.service = new DefaultMatchService(
            nodeId,
            configuration,
            resetService,
            task -> {
                if (Bukkit.isPrimaryThread()) {
                    task.run();
                } else {
                    Bukkit.getScheduler().runTask(plugin, task);
                }
            },
            Instant.now(),
            System.nanoTime(),
            exception -> plugin.getLogger().log(
                Level.WARNING,
                "[warsim-match] 领域事件监听器执行失败，其他监听器继续执行。",
                exception
            )
        );
        serviceRef.set(this.service);
        this.roster = new DefaultRosterService(
            service.snapshot().matchId(), rosterConfiguration, Instant.now()
        );
        this.rosterCoordinator = new MatchRosterCoordinator(service, roster);
        this.presentation = new PaperMatchPresentation(configuration);
        if (objectiveConfiguration.enabled() || ticketConfiguration.enabled()) {
            this.objectiveCoordinator = new ObjectiveMatchCoordinator(
                service,
                objectiveConfiguration,
                ticketConfiguration,
                exception -> plugin.getLogger().log(
                    Level.WARNING,
                    "[warsim-objective] 领域监听器执行失败，其他监听器继续运行。",
                    exception
                ),
                this::announceCapture
            );
            this.objectiveDisplay = objectiveConfiguration.enabled()
                ? new PaperObjectiveDisplay(objectiveConfiguration) : null;
        } else {
            this.objectiveCoordinator = null;
            this.objectiveDisplay = null;
        }
        lastPublishedBattleSnapshot = battleSnapshot();
        service.subscribe(event -> {
            if (event instanceof MatchStateChangedEvent changed) {
                publishBattleSnapshotIfChanged(changed.occurredAt());
                if (changed.transition().nextState() != MatchState.RESETTING) {
                    resetService.cancelIfActiveContextInvalid(
                        "Round Reset context changed to " + changed.transition().nextState()
                    );
                }
            }
        }, false);
        if (configurationError != null) {
            service.failInitialization("Match配置无效");
        } else if (roundResetConfigurationError != null || !roundResetConfiguration.enabled()) {
            service.failInitialization("Round Reset配置无效或未启用");
        } else if (destructionConfigurationError != null) {
            service.failInitialization("Destruction配置无效");
        } else if (objectiveConfigurationError != null) {
            service.failInitialization("Objective配置无效");
        } else if (rosterConfigurationError != null) {
            roster.failInitialization("Roster配置无效：" + rosterConfigurationError);
            service.failInitialization("Roster配置无效");
        } else if (!rosterConfiguration.teamsEnabled()) {
            service.failInitialization("Official Battle启用Match时必须启用阵营系统");
        } else if (configuration.maximumPlayers() > Math.min(100, Bukkit.getMaxPlayers())) {
            service.failInitialization("Match最大人数超过服务端实际容量");
        }
        service.subscribe(event -> plugin.getLogger().info(
            "[warsim-match] matchId=" + event.matchId()
                + " event=" + event.getClass().getSimpleName()
                + " state=" + service.snapshot().state()
                + " revision=" + service.snapshot().lifecycleRevision()
        ), false);
        service.subscribe(event -> {
            if (event instanceof MatchResetStartedEvent) {
                roster.clear();
                if (objectiveDisplay != null) objectiveDisplay.clear();
            }
        }, false);
        battleRuntime.attach(this);
    }

    public PaperMatchCoordinator(
        JavaPlugin plugin,
        String nodeId,
        MatchConfiguration configuration,
        String configurationError,
        RosterConfiguration rosterConfiguration,
        String rosterConfigurationError,
        ObjectiveConfiguration objectiveConfiguration,
        String objectiveConfigurationError,
        TicketConfiguration ticketConfiguration,
        String ticketConfigurationError,
        Predicate<UUID> localSessionActive
    ) {
        this(
            plugin, nodeId, configuration, configurationError,
            RoundResetPaperConfiguration.disabled(), "Round Reset is not configured",
            rosterConfiguration, rosterConfigurationError, objectiveConfiguration, objectiveConfigurationError,
            ticketConfiguration, ticketConfigurationError, null, List.of(), List::of, localSessionActive,
            new PaperBattleRuntime(ignored -> {}),
            new com.warsim.frontline.match.performance.DefaultPerformanceService(
                plugin,
                com.warsim.frontline.match.performance.PerformanceConfiguration.disabled(
                    plugin.getDataFolder().toPath().resolve("performance-reports")
                ),
                nodeId
            )
        );
    }

    public void start() {
        if (tickTaskId >= 0) {
            return;
        }
        tickTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
            plugin,
            this::tick,
            1L,
            1L
        );
    }

    public void playerJoined(Player player) {
        var result = rosterCoordinator.admit(
            player.getUniqueId(), player.getName(), Instant.now()
        );
        if (result.accepted()) {
            presentation.add(player);
            roster.assignment(player.getUniqueId()).ifPresent(assignment ->
                player.sendMessage(RosterMessages.assignment(assignment))
            );
            battleRuntime.publish(new BattleParticipantEvent(
                player.getUniqueId(), true, Instant.now()
            ));
        } else {
            player.sendMessage("§e" + result.message());
        }
    }

    public void playerLeft(Player player) {
        rosterCoordinator.disconnect(player.getUniqueId(), Instant.now());
        presentation.remove(player);
        if (objectiveDisplay != null) objectiveDisplay.remove(player.getUniqueId());
        battleRuntime.publish(new BattleParticipantEvent(
            player.getUniqueId(), false, Instant.now()
        ));
    }

    public List<String> statusLines() {
        MatchSnapshot snapshot = service.snapshot();
        MatchMetricsSnapshot metrics = service.metrics();
        Instant now = Instant.now();
        List<String> lines = new java.util.ArrayList<>(List.of(
            "§6WarSim Match状态",
            "§fmatchId：§a" + snapshot.matchId(),
            "§f模式：§a" + snapshot.modeId(),
            "§f状态：§a" + snapshot.state(),
            "§fRevision：§a" + snapshot.lifecycleRevision(),
            "§f人数：§a" + snapshot.currentPlayers() + "/" + snapshot.maximumPlayers(),
            "§f最低开局人数：§a" + snapshot.minimumPlayers(),
            "§f接受玩家：§a" + snapshot.acceptingPlayers(),
            "§f手动等待：§a" + snapshot.manualWaiting(),
            "§f状态持续：§a" + duration(now, snapshot.stateEnteredAt()),
            "§f热身剩余：§a" + (
                snapshot.state() == MatchState.WARMUP
                    ? remaining(now, snapshot.scheduledStartAt()) : "不适用"
            ),
            "§f回合剩余：§a" + (
                snapshot.state() == MatchState.PLAYING
                    ? remaining(now, snapshot.scheduledEndAt()) : "不适用"
            ),
            "§f结束阶段剩余：§a" + (
                snapshot.state() == MatchState.ENDING
                    ? remaining(now, snapshot.stateEnteredAt().plusSeconds(configuration.endingSeconds()))
                    : "不适用"
            ),
            "§f最近结束原因：§a" + (
                snapshot.endReason() == null ? "无" : snapshot.endReason()
            ),
            "§f中心调度任务：§a" + (tickTaskId >= 0 ? "运行中" : "未运行"),
            "§f最近错误：§a" + (
                snapshot.lastErrorSummary() == null ? "无" : snapshot.lastErrorSummary()
            ),
            "§f累计创建/完成/失败：§a" + metrics.createdMatches() + "/"
                + metrics.completedMatches() + "/" + metrics.failedMatches()
        ));
        lines.addAll(rosterStatusLines());
        lines.addAll(objectiveStatusSummaryLines());
        lines.addAll(ticketStatusLines());
        lines.addAll(destructionStatusLines.get());
        lines.addAll(resetService.statusLines());
        return List.copyOf(lines);
    }

    public void startMatch(CommandSender sender, boolean force) {
        send(sender, service.start(force));
    }

    public void endMatch(CommandSender sender, String reason) {
        send(sender, service.end(MatchEndReason.ADMIN_STOP, reason));
    }

    public void resetMatch(CommandSender sender) {
        send(sender, service.reset());
    }

    public void recoverMatch(CommandSender sender) {
        send(sender, service.recover());
    }

    public PaperNodePublication nodePublication() {
        MatchSnapshot snapshot = service.snapshot();
        MatchNodeStatusMapper.Publication mapped =
            MatchNodeStatusMapper.map(snapshot.state(), configuration.allowMidRoundJoin());
        return new PaperNodePublication(
            mapped.lifecycleState(),
            mapped.availability(),
            mapped.acceptingPlayers() && snapshot.acceptingPlayers(),
            configuration.maximumPlayers()
        );
    }

    public MatchSnapshot snapshot() {
        return service.snapshot();
    }

    public BattleRuntimeSnapshot battleSnapshot() {
        MatchSnapshot snapshot = service.snapshot();
        return new BattleRuntimeSnapshot(
            true, snapshot.matchId(), snapshot.lifecycleRevision(), snapshot.state()
        );
    }

    private void publishBattleSnapshotIfChanged(Instant occurredAt) {
        BattleRuntimeSnapshot current = battleSnapshot();
        BattleRuntimeSnapshot previous = lastPublishedBattleSnapshot;
        if (previous != null && previous.equals(current)) return;
        lastPublishedBattleSnapshot = current;
        if (previous != null) {
            battleRuntime.publish(new BattleMatchChangedEvent(previous, current, occurredAt));
        }
    }

    public void setCombatEligibilityService(CombatEligibilityService combatEligibilityService) {
        this.combatEligibilityService = combatEligibilityService;
    }

    public void setSpawnProtectionService(SpawnProtectionService spawnProtectionService) {
        this.spawnProtectionService = spawnProtectionService;
    }

    public java.util.Optional<BattlePlayerSnapshot> battlePlayer(UUID playerUuid) {
        MatchSnapshot match = service.snapshot();
        var participant = service.participant(playerUuid);
        var assignment = roster.assignment(playerUuid);
        Player player = Bukkit.getPlayer(playerUuid);
        boolean spectator = player != null && player.getGameMode() == GameMode.SPECTATOR;
        var eligibility = combatEligibilityService == null
            ? java.util.Optional.<com.warsim.frontline.api.classes.CombatEligibilitySnapshot>empty()
            : combatEligibilityService.eligibility(playerUuid);
        if (!localSessionActive.test(playerUuid)
            && participant.isEmpty() && assignment.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new BattlePlayerSnapshot(
            playerUuid,
            localSessionActive.test(playerUuid),
            participant.map(com.warsim.frontline.api.match.MatchParticipant::matchId)
                .orElse(match.matchId()),
            participant.map(com.warsim.frontline.api.match.MatchParticipant::state)
                .orElse(null),
            assignment,
            spectator,
            eligibility.map(com.warsim.frontline.api.classes.CombatEligibilitySnapshot::combatState)
                .orElse(PlayerCombatState.ALIVE),
            eligibility.map(com.warsim.frontline.api.classes.CombatEligibilitySnapshot::lifeRevision)
                .orElse(0L)
        ));
    }

    public java.util.Optional<com.warsim.frontline.api.match.MatchParticipant> participant(UUID playerUuid) {
        return service.participant(playerUuid);
    }

    public java.util.Optional<TeamAssignment> assignment(UUID playerUuid) {
        return roster.assignment(playerUuid);
    }

    public TicketService ticketService() {
        return objectiveCoordinator == null ? null : objectiveCoordinator.tickets();
    }

    public CombatRelation combatRelation(UUID first, UUID second) {
        return roster.relation(first, second);
    }

    public List<String> objectiveListLines() {
        if (objectiveConfigurationError != null) {
            return List.of("§cObjective状态：FAILED", "§f错误：§e" + objectiveConfigurationError);
        }
        DefaultObjectiveService objectives = currentObjectives();
        if (objectives == null || !objectiveConfiguration.enabled()) {
            return List.of("§fObjective状态：§e未启用");
        }
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        lines.add("§6WarSim 据点列表");
        for (ObjectiveSnapshot snapshot : objectives.snapshots()) {
            ObjectiveSectorId sectorId = objectives.sectorId(snapshot.objectiveId());
            ObjectiveSectorState sectorState = objectives.sectorState(snapshot.objectiveId());
            lines.add("§f" + snapshot.objectiveId() + " §7(" + snapshot.displayName()
                + ") §fsector=§a" + (sectorId == null ? "none" : sectorId)
                + " §fsectorState=§a" + sectorState
                + " §fowner=§a" + snapshot.owner() + " §fstate=§a" + snapshot.state()
                + " §fprogress=§a" + formatProgress(snapshot.progress()));
        }
        return List.copyOf(lines);
    }

    public List<String> objectiveStatusLines(ObjectiveId id) {
        DefaultObjectiveService objectives = currentObjectives();
        if (objectives == null) return objectiveListLines();
        ObjectiveSnapshot snapshot;
        try {
            snapshot = id == null ? objectives.snapshots().getFirst() : objectives.snapshot(id);
        } catch (RuntimeException exception) {
            return List.of("§c未知据点ID。");
        }
        ObjectiveSectorId sectorId = objectives.sectorId(snapshot.objectiveId());
        ObjectiveSectorState sectorState = objectives.sectorState(snapshot.objectiveId());
        return List.of(
            "§6WarSim 据点状态",
            "§fobjectiveId：§a" + snapshot.objectiveId(),
            "§f名称：§a" + snapshot.displayName(),
            "§fSector：§a" + (sectorId == null ? "none" : sectorId),
            "§fSector状态：§a" + sectorState,
            "§fActive：§a" + objectives.isActiveObjective(snapshot.objectiveId()),
            "§f所有权：§a" + snapshot.owner(),
            "§f状态：§a" + snapshot.state(),
            "§f进度：§a" + formatProgress(snapshot.progress()),
            "§f推进方：§a" + (snapshot.progressingSide() == null ? "无" : snapshot.progressingSide()),
            "§f区域人数：§a" + snapshot.attackersPresent() + " / " + snapshot.defendersPresent(),
            "§f锁定：§a" + snapshot.locked(),
            "§fRevision：§a" + snapshot.revision(),
            "§fmatchId：§a" + snapshot.matchId(),
            "§f最近变化：§a" + snapshot.stateChangedAt()
        );
    }

    public void objectiveLock(CommandSender sender, ObjectiveId id) {
        objectiveOperation(sender, objectives -> objectives.lock(id, Instant.now()), "OBJECTIVE_LOCK", id);
    }

    public void objectiveUnlock(CommandSender sender, ObjectiveId id) {
        objectiveOperation(sender, objectives -> objectives.unlock(id, Instant.now()), "OBJECTIVE_UNLOCK", id);
    }

    public void objectiveReset(CommandSender sender, ObjectiveId id) {
        if (id == null) {
            DefaultObjectiveService objectives = currentObjectives();
            if (!allowObjectiveOperation(sender, objectives)) return;
            for (ObjectiveSnapshot snapshot : objectives.snapshots()) {
                objectives.reset(snapshot.objectiveId(), Instant.now());
            }
            sender.sendMessage("§a全部据点已恢复初始状态。");
            if (objectiveDisplay != null) objectiveDisplay.clear();
            return;
        }
        objectiveOperation(sender, objectives -> objectives.reset(id, Instant.now()), "OBJECTIVE_RESET", id);
    }

    public void objectiveSetOwner(CommandSender sender, ObjectiveId id, ObjectiveOwner owner) {
        objectiveOperation(sender,
            objectives -> objectives.setOwner(id, owner, Instant.now()),
            "OBJECTIVE_SET_OWNER", id);
    }

    public List<String> ticketStatusLines() {
        if (ticketConfigurationError != null) {
            return List.of("§cTicket状态：FAILED", "§f错误：§e" + ticketConfigurationError);
        }
        if (objectiveCoordinator == null || objectiveCoordinator.tickets() == null) {
            return List.of("§fTicket状态：§e未启用");
        }
        TicketSnapshot snapshot = objectiveCoordinator.tickets().snapshot();
        TicketMetricsSnapshot metrics = objectiveCoordinator.tickets().metrics();
        return List.of(
            "§6WarSim 兵力票数",
            "§fmatchId：§a" + snapshot.matchId(),
            "§f进攻方：§a" + snapshot.attackers().current() + "/" + snapshot.attackers().maximum(),
            "§f防守方启用：§a" + snapshot.defenders().enabled(),
            "§f进攻方耗尽：§a" + snapshot.attackersDepleted(),
            "§fRevision：§a" + snapshot.revision(),
            "§f累计变化/奖励：§a" + metrics.ticketChanges() + "/" + metrics.objectiveRewards(),
            "§f最近变化：§a" + (metrics.lastChangedAt() == null ? "无" : metrics.lastChangedAt())
        );
    }

    public void ticketOperation(
        CommandSender sender, TeamSide side, TicketOperationType type, int amount
    ) {
        if (objectiveCoordinator == null || objectiveCoordinator.tickets() == null) {
            sender.sendMessage("§c当前节点未启用票数系统。");
            return;
        }
        TicketOperationResult result = objectiveCoordinator.ticketOperation(new TicketOperation(
            UUID.randomUUID(), side, type, amount, TicketChangeReason.ADMINISTRATOR, Instant.now()
        ));
        sender.sendMessage((result.successful() ? "§a" : "§c") + result.message());
        if (result.successful()) {
            plugin.getLogger().info("[warsim-ticket] operation=" + type
                + " side=" + side + " amount=" + amount
                + " matchId=" + service.snapshot().matchId()
                + " result=SUCCESS");
        }
    }

    public List<String> rosterStatusLines() {
        RosterSnapshot snapshot = roster.snapshot();
        RosterMetricsSnapshot metrics = roster.metrics();
        return List.of(
            "§6WarSim Roster状态",
            "§f启用状态：§a" + (snapshot.state() != RosterState.DISABLED),
            "§f生命周期：§a" + snapshot.state(),
            "§fmatchId一致：§a" + snapshot.matchId().equals(service.snapshot().matchId()),
            "§f进攻方人数：§a" + metrics.attackersMembers(),
            "§f防守方人数：§a" + metrics.defendersMembers(),
            "§f阵营差值：§a" + metrics.balanceDifference(),
            "§f已使用/满员小队：§a" + metrics.squadsWithMembers() + "/" + metrics.fullSquads(),
            "§f断线保留：§a" + metrics.disconnectedReservations(),
            "§fRoster Revision：§a" + snapshot.revision(),
            "§f可修改：§a" + snapshot.modifiable(),
            "§f最近管理员操作：§a" + (
                administratorHistory.isEmpty() ? "无" : administratorHistory.getLast()
            ),
            "§f最近错误：§a" + (snapshot.lastError() == null ? "无" : snapshot.lastError())
        );
    }

    public DefaultRosterService roster() {
        return roster;
    }

    public List<String> teamListLines() {
        RosterSnapshot snapshot = roster.snapshot();
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (TeamSnapshot team : snapshot.teams()) {
            lines.add("§e" + team.displayName() + " §f活动=" + team.activeMembers()
                + " 保留=" + team.reservedMembers() + " 容量=" + team.maximumPlayers());
        }
        return List.copyOf(lines);
    }

    public List<String> squadListLines(TeamSide onlySide) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (SquadSnapshot squad : roster.snapshot().squads()) {
            if (onlySide != null && squad.teamSide() != onlySide) continue;
            lines.add(RosterMessages.squadLine(squad));
        }
        return List.copyOf(lines);
    }

    public List<String> playerRosterLines(UUID playerUuid) {
        return roster.assignment(playerUuid)
            .map(assignment -> List.of(
                "§f玩家：§a" + assignment.currentName(),
                "§f阵营：§a" + assignment.teamSide(),
                "§f小队：§a" + assignment.squadId().map(Enum::name).orElse("无"),
                "§f角色：§a" + assignment.squadRole(),
                "§f连接状态：§a" + assignment.connected()
            ))
            .orElseGet(() -> List.of("§c该玩家没有当前对局分配"));
    }

    public void moveTeam(CommandSender sender, UUID playerUuid, TeamSide side, boolean force) {
        RosterOperationResult result =
            roster.moveTeam(playerUuid, side, force, service.snapshot().state(), Instant.now());
        sendRoster(sender, result);
        recordAdministrator(sender, "TEAM_MOVE", playerUuid, result);
    }

    public void rebalance(CommandSender sender, UUID playerUuid) {
        RosterOperationResult result =
            roster.rebalance(playerUuid, service.snapshot().state(), Instant.now());
        sendRoster(sender, result);
        recordAdministrator(sender, "TEAM_REBALANCE", playerUuid, result);
    }

    public void switchSquad(CommandSender sender, UUID playerUuid, SquadId squad, boolean administrator) {
        RosterOperationResult result = roster.switchSquad(
            playerUuid, squad, service.snapshot().state(), Instant.now(), administrator
        );
        sendRoster(sender, result);
        if (administrator) recordAdministrator(sender, "SQUAD_MOVE", playerUuid, result);
    }

    public void leaveSquad(CommandSender sender, UUID playerUuid, boolean administrator) {
        if (!rosterWritesAllowed()) {
            sender.sendMessage("§c当前对局状态不允许修改小队。");
            return;
        }
        RosterOperationResult result = roster.leaveSquad(playerUuid, Instant.now());
        sendRoster(sender, result);
        if (administrator) recordAdministrator(sender, "SQUAD_REMOVE", playerUuid, result);
    }

    public void transferLeader(
        CommandSender sender, UUID actorUuid, UUID targetUuid, boolean administrator
    ) {
        if (!rosterWritesAllowed()) {
            sender.sendMessage("§c当前对局状态不允许转移队长。");
            return;
        }
        RosterOperationResult result =
            roster.transferLeader(actorUuid, targetUuid, administrator, Instant.now());
        sendRoster(sender, result);
        if (administrator) recordAdministrator(sender, "LEADER_TRANSFER", targetUuid, result);
    }

    private boolean rosterWritesAllowed() {
        return switch (service.snapshot().state()) {
            case WAITING, WARMUP, PLAYING -> true;
            default -> false;
        };
    }

    private void tick() {
        PerformanceSpan performanceSpan = performanceService.startSpan(
            new PerformanceMetricId("match.central_tick"),
            PerformanceComponent.MATCH,
            java.util.Map.of("source", "paper_match_coordinator")
        );
        try {
            Instant now = Instant.now();
            long monotonicNanos = System.nanoTime();
            tickCounter++;
            MatchSnapshot currentSnapshot = service.snapshot();
            performanceService.updateMatchContext(
                currentSnapshot.matchId(),
                currentSnapshot.lifecycleRevision(),
                currentSnapshot.state().name()
            );
            if (tickCounter % 5 == 0) {
                service.tick(monotonicNanos, now);
                boolean newMatch = rosterCoordinator.tick(now);
                if (newMatch) {
                    if (objectiveDisplay != null) objectiveDisplay.clear();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (!localSessionActive.test(player.getUniqueId())) continue;
                        var result = rosterCoordinator.admit(
                            player.getUniqueId(), player.getName(), now
                        );
                        if (!result.accepted()) {
                            plugin.getLogger().warning(
                                "[warsim-squad] 新对局重新接纳失败 playerUuid="
                                    + player.getUniqueId() + " reason=" + result.message()
                            );
                        }
                    }
                }
                presentation.update(service.snapshot(), now);
            }
            if (objectiveCoordinator != null
                && tickCounter % objectiveConfiguration.scanIntervalTicks() == 0) {
                ObjectivePresenceFrame frame = presenceFrame(monotonicNanos, now);
                objectiveCoordinator.tick(frame);
                DefaultObjectiveService objectives = currentObjectives();
                if (objectiveDisplay != null && objectives != null) {
                    objectiveDisplay.attachMetrics(objectives);
                    objectiveDisplay.update(frame, objectives.activeSnapshots());
                }
            }
            BattleRuntimeSnapshot after = battleSnapshot();
            publishBattleSnapshotIfChanged(now);
            battleRuntime.publish(new BattleTickEvent(after, monotonicNanos, tickCounter, now));
            performanceSpan.success();
        } catch (RuntimeException exception) {
            performanceSpan.failure();
            service.failInitialization("Match中心调度发生内部错误");
            plugin.getLogger().log(Level.SEVERE, "[warsim-match] 中心生命周期任务异常。", exception);
        }
    }

    private ObjectivePresenceFrame presenceFrame(long monotonicNanos, Instant now) {
        MatchSnapshot match = service.snapshot();
        java.util.ArrayList<ObjectivePlayerPresence> players = new java.util.ArrayList<>();
        if (match.state() == MatchState.PLAYING) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID playerUuid = player.getUniqueId();
                if (!localSessionActive.test(playerUuid)
                    || player.getGameMode() == GameMode.SPECTATOR) continue;
                var participant = service.participant(playerUuid);
                if (participant.isEmpty()
                    || participant.get().state() != MatchParticipantState.ACTIVE
                    || !participant.get().matchId().equals(match.matchId())) continue;
                if (combatEligibilityService != null
                    && combatEligibilityService.eligibility(playerUuid)
                        .filter(value -> value.eligible()
                            && value.matchId().equals(match.matchId()))
                        .isEmpty()) continue;
                if (spawnProtectionService != null
                    && spawnProtectionService.snapshot(playerUuid).isPresent()) {
                    removeProtectionOnObjectivePresence(player);
                    continue;
                }
                var assignment = roster.assignment(playerUuid);
                if (assignment.isEmpty()
                    || !assignment.get().connected()
                    || !assignment.get().matchId().equals(match.matchId())) continue;
                var location = player.getLocation();
                players.add(new ObjectivePlayerPresence(
                    playerUuid, assignment.get().teamSide(), location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ()
                ));
            }
        }
        return new ObjectivePresenceFrame(
            match.matchId(), match.lifecycleRevision(), monotonicNanos, now, players
        );
    }

    private void removeProtectionOnObjectivePresence(Player player) {
        DefaultObjectiveService objectives = currentObjectives();
        if (objectives == null || spawnProtectionService == null) return;
        Location location = player.getLocation();
        if (location.getWorld() == null) return;
        boolean inside = objectiveConfiguration.definitions().stream()
            .filter(definition -> objectives.isActiveObjective(definition.objectiveId()))
            .anyMatch(definition -> definition.region().contains(
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ()
            ));
        if (!inside) return;
        spawnProtectionService.snapshot(player.getUniqueId()).ifPresent(protection ->
            spawnProtectionService.remove(
                player.getUniqueId(), protection.matchId(), protection.lifeRevision(),
                SpawnProtectionRemovalReason.OBJECTIVE_PRESENCE
            )
        );
    }

    private List<String> objectiveStatusSummaryLines() {
        DefaultObjectiveService objectives = currentObjectives();
        if (objectiveConfigurationError != null) {
            return List.of("§fObjective系统：§cFAILED",
                "§fObjective错误：§e" + objectiveConfigurationError);
        }
        if (objectives == null) return List.of("§fObjective系统：§e未启用");
        List<ObjectiveSnapshot> snapshots = objectives.snapshots();
        ObjectiveMetricsSnapshot metrics = objectives.metrics();
        java.util.ArrayList<String> lines = new java.util.ArrayList<>(List.of(
            "§fObjective系统：§a" + objectives.systemState(),
            "§f据点/争夺：§a" + snapshots.size() + "/"
                + snapshots.stream().filter(s -> s.state() == ObjectiveState.CONTESTED).count(),
            "§f所有权 A/D/N：§a"
                + countOwner(snapshots, ObjectiveOwner.ATTACKERS) + "/"
                + countOwner(snapshots, ObjectiveOwner.DEFENDERS) + "/"
                + countOwner(snapshots, ObjectiveOwner.NEUTRAL),
            "§f扫描耗时(ns)：§a" + metrics.lastScanDurationNanos()
                + " / max " + metrics.maximumScanDurationNanos(),
            "§f最近捕获：§a" + (metrics.lastCaptureAt() == null ? "无" : metrics.lastCaptureAt())
        ));
        if (objectives.sectorsEnabled()) {
            List<ObjectiveSectorSnapshot> sectors = objectives.sectorSnapshots();
            ObjectiveSectorSnapshot current = sectors.stream()
                .filter(sector -> sector.state() == ObjectiveSectorState.ACTIVE)
                .findFirst()
                .orElseGet(() -> sectors.stream()
                    .filter(sector -> sector.scheduledAdvanceAt() != null)
                    .findFirst().orElse(null));
            long completed = sectors.stream()
                .filter(sector -> sector.state() == ObjectiveSectorState.COMPLETED)
                .count();
            long capturedInCurrent = current == null ? 0 : current.objectiveIds().stream()
                .map(objectives::snapshot)
                .filter(snapshot -> snapshot.owner() == ObjectiveOwner.ATTACKERS)
                .count();
            lines.add("§6WarSim Objective Sectors");
            lines.add("§f启用：§atrue");
            lines.add("§f当前Sector：§a" + (current == null
                ? "无" : current.sectorId() + " / " + current.displayName()));
            lines.add("§f状态：§a" + (current == null ? "无" : current.state()));
            lines.add("§f进度：§a" + capturedInCurrent + "/"
                + (current == null ? 0 : current.objectiveIds().size()));
            lines.add("§f下次推进：§a" + (current == null || current.scheduledAdvanceAt() == null
                ? "无" : remaining(Instant.now(), current.scheduledAdvanceAt())));
            lines.add("§fSector数量：§a" + sectors.size());
            lines.add("§f已完成：§a" + completed);
            lines.add("§f最近推进：§a" + (objectives.lastSectorAdvancedAt() == null
                ? "无" : objectives.lastSectorAdvancedAt()));
        }
        return List.copyOf(lines);
    }

    private void objectiveOperation(
        CommandSender sender,
        java.util.function.Function<DefaultObjectiveService, ObjectiveOperationResult> operation,
        String name,
        ObjectiveId id
    ) {
        DefaultObjectiveService objectives = currentObjectives();
        if (!allowObjectiveOperation(sender, objectives)) return;
        try {
            ObjectiveOperationResult result = operation.apply(objectives);
            sender.sendMessage((result.successful() ? "§a" : "§c") + result.message());
            if (result.successful()) {
                plugin.getLogger().info("[warsim-objective] operation=" + name
                    + " objectiveId=" + id + " matchId=" + service.snapshot().matchId()
                    + " result=SUCCESS");
            }
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c未知据点ID。");
        }
    }

    private boolean allowObjectiveOperation(
        CommandSender sender, DefaultObjectiveService objectives
    ) {
        if (objectives == null) {
            sender.sendMessage("§c当前节点未启用据点系统。");
            return false;
        }
        MatchState state = service.snapshot().state();
        if (state != MatchState.WAITING
            && state != MatchState.WARMUP
            && state != MatchState.PLAYING) {
            sender.sendMessage("§c当前对局状态不允许修改据点。");
            return false;
        }
        return true;
    }

    private DefaultObjectiveService currentObjectives() {
        return objectiveCoordinator == null ? null : objectiveCoordinator.objectives();
    }

    private void announceCapture(ObjectiveCapturedEvent event) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            service.participant(player.getUniqueId()).ifPresent(participant -> {
                if (participant.matchId().equals(event.matchId())) {
                    player.sendMessage("§6据点 " + event.objectiveId()
                        + " 已被 " + event.capturedBy() + " 占领！");
                }
            });
        }
    }

    private static long countOwner(
        List<ObjectiveSnapshot> snapshots, ObjectiveOwner owner
    ) {
        return snapshots.stream().filter(snapshot -> snapshot.owner() == owner).count();
    }

    private static String formatProgress(double progress) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", progress * 100);
    }

    private static void send(CommandSender sender, MatchOperationResult result) {
        sender.sendMessage((result.accepted() ? "§a" : "§c") + result.message());
    }

    private static void sendRoster(CommandSender sender, RosterOperationResult result) {
        sender.sendMessage((result.successful() ? "§a" : "§c") + result.message());
    }

    private void recordAdministrator(
        CommandSender sender, String operation, UUID target, RosterOperationResult result
    ) {
        if (!result.successful()) return;
        String entry = operation + " actor=" + sender.getName() + " target=" + target
            + " matchId=" + service.snapshot().matchId() + " at=" + Instant.now();
        administratorHistory.addLast(entry);
        while (administratorHistory.size() > 50) administratorHistory.removeFirst();
        plugin.getLogger().info("[warsim-squad] " + entry + " result=SUCCESS");
    }

    private static String duration(Instant now, Instant start) {
        return Duration.between(start, now).toSeconds() + "秒";
    }

    private static String remaining(Instant now, Instant deadline) {
        if (deadline == null) {
            return "不适用";
        }
        return Math.max(0, Duration.between(now, deadline).toSeconds()) + "秒";
    }

    @Override
    public void close() {
        if (tickTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
        service.close();
        resetService.close();
        presentation.close();
        if (objectiveDisplay != null) objectiveDisplay.close();
        if (objectiveCoordinator != null) objectiveCoordinator.close();
        rosterCoordinator.close();
        battleRuntime.detach(this);
    }
}
