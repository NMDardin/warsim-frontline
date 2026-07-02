package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.admin.WarSimCommandRegistry;
import com.warsim.frontline.api.battle.WarSimBattleRuntime;
import com.warsim.frontline.api.performance.PerformanceComponent;
import com.warsim.frontline.api.performance.PerformanceContributor;
import com.warsim.frontline.api.performance.PerformanceMetricId;
import com.warsim.frontline.api.performance.PerformanceMetricSnapshot;
import com.warsim.frontline.api.performance.PerformancePercentiles;
import com.warsim.frontline.api.performance.PerformanceService;
import com.warsim.frontline.api.classes.CombatLoadoutProvisioningService;
import com.warsim.frontline.api.weapon.WeaponMetricsSnapshot;
import com.warsim.frontline.api.weapon.WeaponService;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class WarSimWeaponsPlugin extends JavaPlugin {
    private ExecutorService configExecutor;
    private WeaponCoordinator coordinator;
    private AutoCloseable commandRegistration;
    private AutoCloseable performanceRegistration;
    private boolean loadoutServiceRegistered;
    private volatile boolean closing;

    @Override
    public void onEnable() {
        getLogger().info("[warsim-weapons] 正在加载独立武器插件配置。");
        configExecutor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("warsim-weapons-config").factory()
        );
        CompletableFuture.supplyAsync(
            () -> WeaponConfigLoader.load(this, getLogger()), configExecutor
        ).whenComplete((configuration, failure) -> {
            if (failure != null) {
                getLogger().log(Level.SEVERE, "[warsim-weapons] 配置加载任务失败。", failure);
                configuration = new WeaponPaperConfiguration(
                    com.warsim.frontline.api.weapon.WeaponConfiguration.disabled(),
                    true, true, failure.getMessage()
                );
            }
            WeaponPaperConfiguration loaded = configuration;
            if (!closing) Bukkit.getScheduler().runTask(this, () -> initialize(loaded));
        });
    }

    private void initialize(WeaponPaperConfiguration configuration) {
        WarSimBattleRuntime runtime =
            getServer().getServicesManager().load(WarSimBattleRuntime.class);
        WarSimCommandRegistry commands =
            getServer().getServicesManager().load(WarSimCommandRegistry.class);
        PerformanceService performance =
            getServer().getServicesManager().load(PerformanceService.class);
        if (runtime == null || commands == null) {
            getLogger().severe("[warsim-weapons] WarSim主插件服务不可用，武器插件无法启用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!configuration.core().enabled()) {
            getLogger().warning("[warsim-weapons] 武器系统已禁用"
                + (configuration.error() == null ? "。" : "：" + configuration.error()));
            var disabledService = new com.warsim.frontline.weapons.DefaultWeaponService(
                configuration.core(),
                configuration.error() == null
                    ? com.warsim.frontline.api.weapon.WeaponSystemState.DISABLED
                    : com.warsim.frontline.api.weapon.WeaponSystemState.FAILED
            );
            var gateway = new CraftEngineWeaponGateway(disabledService.definitions());
            commandRegistration = register(commands, new WeaponCommandExtension(
                disabledService, gateway, runtime, configuration
            ));
            registerPerformanceContributor(performance, disabledService);
            return;
        }
        coordinator = new WeaponCoordinator(this, runtime, configuration);
        getServer().getServicesManager().register(
            CombatLoadoutProvisioningService.class,
            coordinator.loadoutProvider(),
            this,
            org.bukkit.plugin.ServicePriority.Normal
        );
        loadoutServiceRegistered = true;
        commandRegistration = register(commands, new WeaponCommandExtension(
            coordinator.service(), coordinator.gateway(), runtime, configuration
        ));
        registerPerformanceContributor(performance, coordinator.service());
        getLogger().info("[warsim-weapons] 启动完成，已加载武器："
            + coordinator.service().definitions().stream()
                .map(value -> value.weaponId().value()).toList());
    }

    private AutoCloseable register(
        WarSimCommandRegistry registry, WeaponCommandExtension extension
    ) {
        try {
            return registry.register(extension);
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "[warsim-weapons] 注册/warsim weapon失败。", exception);
            getServer().getPluginManager().disablePlugin(this);
            return () -> {};
        }
    }

    private void registerPerformanceContributor(PerformanceService performance, WeaponService service) {
        if (performance == null) return;
        try {
            performanceRegistration = performance.registerContributor(new WeaponsPerformanceContributor(service));
        } catch (RuntimeException exception) {
            getLogger().log(Level.WARNING, "[warsim-weapons] Performance contributor registration failed.", exception);
        }
    }

    private record WeaponsPerformanceContributor(WeaponService service) implements PerformanceContributor {
        private static final PerformanceMetricId SHOT_PROCESSING =
            new PerformanceMetricId("weapon.shot_processing");

        @Override public String name() {
            return "warsim-weapons-paper";
        }

        @Override public List<PerformanceMetricSnapshot> snapshotMetrics() {
            WeaponMetricsSnapshot metrics = service.metrics();
            OptionalLong last = metrics.lastShotProcessingNanos() > 0
                ? OptionalLong.of(metrics.lastShotProcessingNanos()) : OptionalLong.empty();
            OptionalLong maximum = metrics.maximumShotProcessingNanos() > 0
                ? OptionalLong.of(metrics.maximumShotProcessingNanos()) : OptionalLong.empty();
            return List.of(new PerformanceMetricSnapshot(
                SHOT_PROCESSING,
                PerformanceComponent.WEAPON,
                null,
                0,
                metrics.shotsRequested(),
                metrics.shotsFired(),
                metrics.shotsRejected(),
                last,
                OptionalLong.empty(),
                maximum,
                OptionalLong.empty(),
                PerformancePercentiles.unavailable(),
                0.0,
                metrics.lastShotAt(),
                null
            ));
        }
    }

    @Override
    public void onDisable() {
        closing = true;
        if (performanceRegistration != null) {
            try {
                performanceRegistration.close();
            } catch (Exception exception) {
                getLogger().log(Level.WARNING, "[warsim-weapons] Performance contributor unregistration failed.", exception);
            }
        }
        if (commandRegistration != null) {
            try {
                commandRegistration.close();
            } catch (Exception exception) {
                getLogger().log(Level.WARNING, "[warsim-weapons] 注销命令扩展失败。", exception);
            }
        }
        if (loadoutServiceRegistered) {
            getServer().getServicesManager().unregister(
                CombatLoadoutProvisioningService.class,
                coordinator == null ? null : coordinator.loadoutProvider()
            );
            loadoutServiceRegistered = false;
        }
        if (coordinator != null) coordinator.close();
        if (configExecutor != null) configExecutor.shutdownNow();
        getLogger().info("[warsim-weapons] 独立武器插件已安全关闭。");
    }
}
