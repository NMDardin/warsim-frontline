package com.warsim.frontline.match.loadtest;

import com.warsim.frontline.api.loadtest.*;
import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.performance.PerformanceService;
import com.warsim.frontline.api.roster.SquadId;
import com.warsim.frontline.api.roster.TeamSide;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class DefaultLoadScenarioService implements LoadScenarioService {
    private final JavaPlugin plugin;
    private final boolean enabled;
    private final PerformanceService performanceService;
    private final Supplier<MatchState> matchStateSupplier;
    private final LoadScenarioTemplateFactory templates = new LoadScenarioTemplateFactory();
    private final AtomicLong validations = new AtomicLong();
    private final AtomicLong validationFailures = new AtomicLong();
    private final AtomicLong preparations = new AtomicLong();
    private final AtomicLong preparationFailures = new AtomicLong();
    private final AtomicLong cleans = new AtomicLong();
    private final AtomicLong cleanFailures = new AtomicLong();

    private volatile LoadScenarioState state = LoadScenarioState.DISABLED;
    private volatile LoadMapDefinition map;
    private volatile List<LoadScenarioDefinition> scenarios = List.of();
    private volatile LoadScenarioId preparedScenarioId;
    private volatile List<String> validationMessages = List.of();
    private volatile Instant lastValidationAt;
    private volatile Instant lastPreparationAt;

    public DefaultLoadScenarioService(
        JavaPlugin plugin,
        boolean enabled,
        PerformanceService performanceService,
        Supplier<MatchState> matchStateSupplier
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.enabled = enabled;
        this.performanceService = performanceService;
        this.matchStateSupplier = Objects.requireNonNull(matchStateSupplier, "matchStateSupplier");
        reload();
    }

    public synchronized void reload() {
        if (!enabled) {
            state = LoadScenarioState.DISABLED;
            return;
        }
        try {
            ensureResource("config/load-maps.yml");
            ensureResource("config/load-scenarios.yml");
            File mapFile = new File(plugin.getDataFolder(), "config/load-maps.yml");
            File scenarioFile = new File(plugin.getDataFolder(), "config/load-scenarios.yml");
            YamlConfiguration mapYaml = YamlConfiguration.loadConfiguration(mapFile);
            YamlConfiguration scenarioYaml = YamlConfiguration.loadConfiguration(scenarioFile);
            if (!mapYaml.getBoolean("enabled", true)) {
                state = LoadScenarioState.DISABLED;
                return;
            }
            map = templates.map(mapYaml);
            scenarios = templates.scenarios(scenarioYaml, map);
            state = plugin.getServer().getWorld(map.worldName()) == null
                ? LoadScenarioState.UNLOADED : LoadScenarioState.READY;
            validationMessages = state == LoadScenarioState.UNLOADED
                ? List.of("测试世界不存在：" + map.worldName() + "；不会自动创建世界。")
                : List.of(LoadScenarioTemplateFactory.DEFAULT_NOTICE);
        } catch (RuntimeException exception) {
            state = LoadScenarioState.FAILED;
            validationMessages = List.of("LoadMap配置加载失败：" + exception.getMessage());
            plugin.getLogger().log(Level.WARNING, "[warsim-loadmap] load map configuration failed", exception);
        }
    }

    @Override public LoadScenarioSnapshot snapshot() {
        return new LoadScenarioSnapshot(
            state,
            map == null ? null : map.mapId(),
            preparedScenarioId,
            Instant.now(),
            map == null ? 0 : 1,
            scenarios.size(),
            validationMessages,
            metrics()
        );
    }

    @Override public List<LoadMapDefinition> maps() {
        return map == null ? List.of() : List.of(map);
    }

    @Override public List<LoadScenarioDefinition> scenarios() {
        return scenarios;
    }

    @Override public Optional<LoadScenarioDefinition> scenario(LoadScenarioId scenarioId) {
        return scenarios.stream().filter(value -> value.scenarioId().equals(scenarioId)).findFirst();
    }

    @Override public synchronized LoadScenarioValidationResult validate(LoadMapId mapId) {
        validations.incrementAndGet();
        lastValidationAt = Instant.now();
        if (state == LoadScenarioState.DISABLED) {
            return failValidation("LoadMap子系统未启用。", List.of("状态=DISABLED"));
        }
        if (map == null || !map.mapId().equals(mapId)) {
            return failValidation("未知LoadMap。", List.of(String.valueOf(mapId)));
        }
        state = LoadScenarioState.VALIDATING;
        List<String> details = validateInternal();
        boolean success = details.stream().noneMatch(line -> line.startsWith("ERROR"));
        if (success) {
            state = plugin.getServer().getWorld(map.worldName()) == null
                ? LoadScenarioState.UNLOADED : LoadScenarioState.READY;
            validationMessages = details;
            return new LoadScenarioValidationResult(true, "LoadMap验证完成。", details);
        }
        validationFailures.incrementAndGet();
        state = LoadScenarioState.FAILED;
        validationMessages = details;
        return new LoadScenarioValidationResult(false, "LoadMap验证失败。", details);
    }

    @Override public synchronized LoadScenarioPreparationResult prepare(LoadScenarioId scenarioId) {
        preparations.incrementAndGet();
        lastPreparationAt = Instant.now();
        if (state == LoadScenarioState.DISABLED) {
            return failPrepare("LoadMap子系统未启用。");
        }
        if (matchStateSupplier.get() == MatchState.PLAYING) {
            return failPrepare("PLAYING状态拒绝准备负载测试场景。");
        }
        if (preparedScenarioId != null) {
            return failPrepare("已有场景处于PREPARED，请先执行clean。");
        }
        Optional<LoadScenarioDefinition> definition = scenario(scenarioId);
        if (definition.isEmpty()) {
            return failPrepare("未知场景：" + scenarioId);
        }
        LoadScenarioValidationResult validation = validate(definition.get().mapId());
        if (!validation.successful()) {
            return failPrepare("场景准备前验证失败。");
        }
        preparedScenarioId = scenarioId;
        state = LoadScenarioState.PREPARED;
        validationMessages = List.of(
            "场景已在内存中准备：" + scenarioId,
            "prepare不会创建世界、不会改方块、不会传送玩家、不会运行synthetic。"
        );
        updatePerformanceReference();
        return new LoadScenarioPreparationResult(true, "场景内存上下文已准备。", snapshot());
    }

    @Override public synchronized LoadScenarioPreparationResult clean() {
        cleans.incrementAndGet();
        if (state == LoadScenarioState.CLOSED) {
            return new LoadScenarioPreparationResult(true, "LoadMap服务已关闭。", snapshot());
        }
        state = LoadScenarioState.CLEANING;
        preparedScenarioId = null;
        state = map == null ? LoadScenarioState.DISABLED
            : plugin.getServer().getWorld(map.worldName()) == null ? LoadScenarioState.UNLOADED
            : LoadScenarioState.READY;
        validationMessages = List.of("场景上下文已清理；没有修改世界或Match。");
        updatePerformanceReference();
        return new LoadScenarioPreparationResult(true, "场景上下文已清理。", snapshot());
    }

    @Override public synchronized void close() {
        try {
            clean();
        } catch (RuntimeException exception) {
            cleanFailures.incrementAndGet();
            plugin.getLogger().log(Level.WARNING, "[warsim-loadmap] clean during close failed", exception);
        }
        state = LoadScenarioState.CLOSED;
        preparedScenarioId = null;
        updatePerformanceReference();
    }

    private LoadScenarioValidationResult failValidation(String message, List<String> details) {
        validationFailures.incrementAndGet();
        validationMessages = List.copyOf(details);
        return new LoadScenarioValidationResult(false, message, details);
    }

    private LoadScenarioPreparationResult failPrepare(String message) {
        preparationFailures.incrementAndGet();
        validationMessages = List.of(message);
        return new LoadScenarioPreparationResult(false, message, snapshot());
    }

    private List<String> validateInternal() {
        List<String> details = new ArrayList<>();
        if (plugin.getServer().getWorld(map.worldName()) == null) {
            details.add("ERROR: 世界不存在：" + map.worldName() + "；不会自动创建。");
        }
        details.add(LoadScenarioTemplateFactory.DEFAULT_NOTICE);
        Set<String> zoneIds = new HashSet<>();
        for (LoadZoneDefinition zone : map.zones()) {
            if (!zoneIds.add(zone.zoneId())) details.add("ERROR: 重复区域ID " + zone.zoneId());
            if (!zone.center().world().equals(map.worldName())) details.add("ERROR: 区域世界不匹配 " + zone.zoneId());
            if (!finiteZone(zone)) details.add("ERROR: 区域坐标非法 " + zone.zoneId());
            if (Math.abs(zone.minimumX()) > 500 || Math.abs(zone.maximumX()) > 500
                || Math.abs(zone.minimumZ()) > 500 || Math.abs(zone.maximumZ()) > 500) {
                details.add("ERROR: 区域超过安全边界 " + zone.zoneId());
            }
        }
        Set<String> scenarioIds = new HashSet<>();
        for (LoadScenarioDefinition scenario : scenarios) {
            if (!scenarioIds.add(scenario.scenarioId().value())) {
                details.add("ERROR: 重复场景ID " + scenario.scenarioId());
            }
            validateScenario(details, scenario, zoneIds);
        }
        if (details.stream().noneMatch(value -> value.startsWith("ERROR"))) {
            details.add("OK: 固定区域=" + map.zones().size() + " 固定场景=" + scenarios.size());
        }
        return List.copyOf(details);
    }

    private void validateScenario(
        List<String> details, LoadScenarioDefinition scenario, Set<String> zoneIds
    ) {
        for (String zoneId : scenario.zoneIds()) {
            if (!zoneIds.contains(zoneId)) details.add("ERROR: 场景引用未知区域 " + scenario.scenarioId() + "/" + zoneId);
        }
        Set<String> slotIds = new HashSet<>();
        Map<TeamSide, Integer> teamCounts = new EnumMap<>(TeamSide.class);
        Map<TeamSide, Map<SquadId, Integer>> squadCounts = new EnumMap<>(TeamSide.class);
        for (LoadSpawnDefinition slot : scenario.slots()) {
            if (!slotIds.add(slot.slotId())) details.add("ERROR: 重复槽位ID " + scenario.scenarioId() + "/" + slot.slotId());
            if (!slot.coordinate().world().equals(map.worldName())) details.add("ERROR: 槽位世界错误 " + slot.slotId());
            if (!slotInsideAnyScenarioZone(slot, scenario)) details.add("ERROR: 槽位不在场景区域内 " + slot.slotId());
            teamCounts.merge(slot.teamSide(), 1, Integer::sum);
            squadCounts.computeIfAbsent(slot.teamSide(), ignored -> new EnumMap<>(SquadId.class))
                .merge(slot.squadId(), 1, Integer::sum);
        }
        if (scenario.type() != LoadScenarioType.IDLE) {
            if (teamCounts.getOrDefault(TeamSide.ATTACKERS, 0) != 50
                || teamCounts.getOrDefault(TeamSide.DEFENDERS, 0) != 50) {
                details.add("ERROR: 场景不是50v50槽位 " + scenario.scenarioId());
            }
            for (TeamSide side : TeamSide.values()) {
                for (SquadId squad : SquadId.values()) {
                    int count = squadCounts.getOrDefault(side, Map.of()).getOrDefault(squad, 0);
                    if (count != 5) details.add("ERROR: 小队槽位不是5人 " + scenario.scenarioId() + "/" + side + "/" + squad);
                }
            }
        }
        for (LoadLaneDefinition lane : scenario.lanes()) {
            if (!lane.start().world().equals(map.worldName()) || !lane.end().world().equals(map.worldName())) {
                details.add("ERROR: 通道世界错误 " + scenario.scenarioId() + "/" + lane.laneId());
            }
            if (scenario.type() == LoadScenarioType.WEAPON_BLOCKED && lane.blocker() == null) {
                details.add("ERROR: 遮挡通道缺少阻挡位置 " + lane.laneId());
            }
        }
    }

    private boolean slotInsideAnyScenarioZone(LoadSpawnDefinition slot, LoadScenarioDefinition scenario) {
        return map.zones().stream()
            .filter(zone -> scenario.zoneIds().contains(zone.zoneId()))
            .anyMatch(zone -> zone.contains(slot.coordinate()));
    }

    private boolean finiteZone(LoadZoneDefinition zone) {
        return Double.isFinite(zone.minimumX()) && Double.isFinite(zone.minimumY())
            && Double.isFinite(zone.minimumZ()) && Double.isFinite(zone.maximumX())
            && Double.isFinite(zone.maximumY()) && Double.isFinite(zone.maximumZ());
    }

    private LoadScenarioMetrics metrics() {
        return new LoadScenarioMetrics(
            validations.get(), validationFailures.get(), preparations.get(),
            preparationFailures.get(), cleans.get(), cleanFailures.get(),
            lastValidationAt, lastPreparationAt
        );
    }

    private void ensureResource(String resource) {
        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            plugin.saveResource(resource, false);
        }
    }

    private void updatePerformanceReference() {
        if (performanceService instanceof com.warsim.frontline.match.performance.DefaultPerformanceService service) {
            service.updateLoadScenarioReference(preparedScenarioId == null ? null : preparedScenarioId.value());
        }
    }
}
