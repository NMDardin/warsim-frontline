package com.warsim.frontline.match.performance;

import com.warsim.frontline.api.performance.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class DefaultPerformanceService implements PerformanceService {
    private final JavaPlugin plugin;
    private final Logger logger;
    private final Object lock = new Object();
    private final Map<PerformanceMetricId, PerformanceWindow> windows = new LinkedHashMap<>();
    private final Map<String, PerformanceContributor> contributors = new ConcurrentHashMap<>();
    private final ArrayDeque<PerformanceAlert> alerts = new ArrayDeque<>();
    private final ArrayDeque<Path> reports = new ArrayDeque<>();
    private final Map<PerformanceMetricId, Long> lastAlertMillis = new LinkedHashMap<>();
    private final SyntheticLoadExecutor synthetic = new SyntheticLoadExecutor();
    private final ThreadPoolExecutor syntheticExecutor;
    private final AtomicBoolean syntheticCancel = new AtomicBoolean();
    private final AtomicLong totalSamples = new AtomicLong();
    private final AtomicLong rejectedSamples = new AtomicLong();
    private final AtomicLong droppedSamples = new AtomicLong();
    private final AtomicLong alertCount = new AtomicLong();
    private final AtomicLong reportsExported = new AtomicLong();
    private final AtomicLong syntheticStarted = new AtomicLong();
    private final AtomicLong syntheticCompleted = new AtomicLong();
    private final AtomicLong syntheticFailed = new AtomicLong();
    private final AtomicLong syntheticCancelled = new AtomicLong();
    private volatile PerformanceConfiguration configuration;
    private volatile PerformanceServiceState state;
    private volatile UUID matchId;
    private volatile long lifecycleRevision;
    private volatile String matchState = "UNAVAILABLE";
    private volatile String nodeId = "unknown";
    private volatile String loadScenarioReference;
    private volatile Instant lastSampleAt;
    private volatile Future<?> runningSynthetic;
    private volatile SyntheticLoadResult lastSyntheticResult;

    public DefaultPerformanceService(
        JavaPlugin plugin, PerformanceConfiguration configuration, String nodeId
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.state = configuration.enabled()
            ? PerformanceServiceState.ACTIVE : PerformanceServiceState.DISABLED;
        this.syntheticExecutor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(configuration.syntheticExecutorQueueCapacity()),
            Thread.ofPlatform().name("warsim-synthetic-load").factory(),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void configure(PerformanceConfiguration configuration, String nodeId) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (state != PerformanceServiceState.CLOSED
            && state != PerformanceServiceState.SYNTHETIC_RUNNING) {
            state = configuration.enabled()
                ? PerformanceServiceState.ACTIVE : PerformanceServiceState.DISABLED;
        }
    }

    @Override public PerformanceServiceState state() { return state; }

    @Override
    public PerformanceSpan startSpan(
        PerformanceMetricId metricId, PerformanceComponent component, Map<String, String> context
    ) {
        if (state == PerformanceServiceState.DISABLED || state == PerformanceServiceState.CLOSED) {
            return NoOpPerformanceSpan.INSTANCE;
        }
        try {
            return new OneShotPerformanceSpan(this, metricId, component, safeContext(context), matchId, lifecycleRevision);
        } catch (RuntimeException exception) {
            rejectedSamples.incrementAndGet();
            return NoOpPerformanceSpan.INSTANCE;
        }
    }

    @Override
    public void record(PerformanceSample sample) {
        if (state == PerformanceServiceState.DISABLED || state == PerformanceServiceState.CLOSED) {
            rejectedSamples.incrementAndGet();
            return;
        }
        synchronized (lock) {
            PerformanceWindow window = windows.get(sample.metricId());
            if (window == null) {
                if (windows.size() >= configuration.maximumMetrics()) {
                    droppedSamples.incrementAndGet();
                    return;
                }
                window = new PerformanceWindow(sample.metricId(), sample.component(), configuration.samplesPerMetric());
                windows.put(sample.metricId(), window);
            }
            window.add(sample, configuration.warningThresholdNanos());
            totalSamples.incrementAndGet();
            lastSampleAt = sample.sampledAt();
            maybeAlert(sample);
        }
    }

    @Override
    public AutoCloseable registerContributor(PerformanceContributor contributor) {
        Objects.requireNonNull(contributor, "contributor");
        String name = contributor.name().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_.-]{1,48}") || contributors.size() >= 32) {
            throw new IllegalArgumentException("Invalid or excessive performance contributor");
        }
        contributors.put(name, contributor);
        return () -> contributors.remove(name, contributor);
    }

    @Override
    public PerformanceSnapshot snapshot(Optional<PerformanceComponent> component) {
        List<PerformanceMetricSnapshot> metricSnapshots = new ArrayList<>();
        synchronized (lock) {
            for (PerformanceWindow window : windows.values()) {
                PerformanceMetricSnapshot snapshot = window.snapshot();
                if (component.isEmpty() || snapshot.component() == component.get()) {
                    metricSnapshots.add(snapshot);
                }
            }
        }
        for (PerformanceContributor contributor : contributors.values()) {
            try {
                for (PerformanceMetricSnapshot snapshot : contributor.snapshotMetrics()) {
                    if (component.isEmpty() || snapshot.component() == component.get()) {
                        metricSnapshots.add(snapshot);
                    }
                }
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "[warsim-perf] performance contributor failed: " + contributor.name(), exception);
            }
        }
        metricSnapshots.sort(Comparator.comparing(s -> s.metricId().value()));
        return new PerformanceSnapshot(
            state, nodeId, matchId, lifecycleRevision, matchState, Instant.now(),
            configurationSummary(), List.copyOf(metricSnapshots), alerts(),
            serviceMetrics(), lastSyntheticResult, loadScenarioReference
        );
    }

    @Override
    public List<PerformanceAlert> alerts() {
        synchronized (lock) {
            return List.copyOf(alerts);
        }
    }

    @Override
    public List<SyntheticLoadScenario> syntheticScenarios() {
        int warmup = configuration.syntheticDefaultWarmupIterations();
        int measurement = configuration.syntheticDefaultMeasurementIterations();
        int max = configuration.syntheticMaximumIterations();
        long duration = configuration.syntheticMaximumDurationMillis();
        return List.of(
            new SyntheticLoadScenario("ROSTER_100_PLAYER_ASSIGNMENT",
                SyntheticLoadScenarioType.ROSTER_100_PLAYER_ASSIGNMENT,
                "Roster 100 player assignment", warmup, measurement, max, duration,
                "roster_100", List.of(PerformanceComponent.ROSTER), 101),
            new SyntheticLoadScenario("OBJECTIVE_100_PLAYER_PRESENCE",
                SyntheticLoadScenarioType.OBJECTIVE_100_PLAYER_PRESENCE,
                "Objective 100 player presence", warmup, measurement, max, duration,
                "objective_100_single", List.of(PerformanceComponent.OBJECTIVE), 100L * 20L),
            new SyntheticLoadScenario("WEAPON_100_SHOOTERS_100_CANDIDATES",
                SyntheticLoadScenarioType.WEAPON_100_SHOOTERS_100_CANDIDATES,
                "Weapon 100 shooters x 100 candidates", warmup, measurement, max, duration,
                "weapon_100_medium", List.of(PerformanceComponent.WEAPON), 100L * 100L),
            new SyntheticLoadScenario("MIXED_BATTLE_TICK",
                SyntheticLoadScenarioType.MIXED_BATTLE_TICK,
                "Mixed battle pure Java tick", warmup, measurement, max, duration,
                "mixed_100", List.of(PerformanceComponent.MATCH, PerformanceComponent.ROSTER,
                    PerformanceComponent.OBJECTIVE, PerformanceComponent.WEAPON), 101 + 2000 + 10_000)
        );
    }

    @Override
    public Optional<SyntheticLoadScenario> syntheticScenario(String id) {
        return syntheticScenarios().stream()
            .filter(scenario -> scenario.id().equalsIgnoreCase(id))
            .findFirst();
    }

    @Override
    public SyntheticDryRunPlan dryRun(String scenarioId) {
        SyntheticLoadScenario scenario = syntheticScenario(scenarioId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown synthetic scenario"));
        boolean executable = configuration.syntheticEnabled()
            && state != PerformanceServiceState.CLOSED
            && state != PerformanceServiceState.FAILED
            && runningSynthetic == null;
        String message = executable
            ? "Synthetic execution is configured but not run by dryrun."
            : "Synthetic execution is disabled, closed, failed, or already running.";
        return new SyntheticDryRunPlan(
            scenario, executable, message, scenario.warmupIterations(),
            scenario.measurementIterations(), scenario.estimatedOperations(),
            scenario.components()
        );
    }

    @Override
    public boolean startSynthetic(String scenarioId, int measurementIterations) {
        if (!configuration.syntheticEnabled() || state == PerformanceServiceState.CLOSED
            || state == PerformanceServiceState.FAILED || runningSynthetic != null
            || "PLAYING".equalsIgnoreCase(matchState)) {
            return false;
        }
        SyntheticLoadScenario scenario = syntheticScenario(scenarioId).orElse(null);
        if (scenario == null) return false;
        int measurements = measurementIterations > 0
            ? Math.min(measurementIterations, configuration.syntheticMaximumIterations())
            : scenario.measurementIterations();
        UUID runId = UUID.randomUUID();
        syntheticCancel.set(false);
        try {
            state = PerformanceServiceState.SYNTHETIC_RUNNING;
            syntheticStarted.incrementAndGet();
            runningSynthetic = syntheticExecutor.submit(() -> {
                try {
                    SyntheticLoadResult result = synthetic.run(runId, scenario, measurements, configuration, syntheticCancel::get);
                    lastSyntheticResult = result;
                    if (result.cancelled()) syntheticCancelled.incrementAndGet();
                    else if (result.completed()) syntheticCompleted.incrementAndGet();
                    else syntheticFailed.incrementAndGet();
                } catch (Exception exception) {
                    syntheticFailed.incrementAndGet();
                    lastSyntheticResult = new SyntheticLoadResult(
                        runId, scenario.id(), scenario.type(), false, false,
                        sanitize(exception.getClass().getSimpleName()),
                        scenario.warmupIterations(), measurements, 0, 0,
                        java.util.OptionalLong.empty(), PerformancePercentiles.unavailable(),
                        java.util.OptionalLong.empty(), 0, Instant.now(), Instant.now(),
                        Map.of("input", scenario.id())
                    );
                    logger.log(Level.WARNING, "[warsim-perf] synthetic load failed", exception);
                } finally {
                    runningSynthetic = null;
                    if (state == PerformanceServiceState.SYNTHETIC_RUNNING) {
                        state = configuration.enabled()
                            ? PerformanceServiceState.ACTIVE : PerformanceServiceState.DISABLED;
                    }
                }
            });
            return true;
        } catch (RuntimeException exception) {
            runningSynthetic = null;
            state = configuration.enabled() ? PerformanceServiceState.ACTIVE : PerformanceServiceState.DISABLED;
            syntheticFailed.incrementAndGet();
            return false;
        }
    }

    @Override public Optional<SyntheticLoadResult> syntheticStatus() {
        return Optional.ofNullable(lastSyntheticResult);
    }

    @Override
    public boolean cancelSynthetic() {
        syntheticCancel.set(true);
        Future<?> current = runningSynthetic;
        if (current == null) return false;
        current.cancel(true);
        runningSynthetic = null;
        syntheticCancelled.incrementAndGet();
        state = configuration.enabled() ? PerformanceServiceState.ACTIVE : PerformanceServiceState.DISABLED;
        return true;
    }

    @Override
    public PerformanceExportResult exportReport() {
        try {
            Files.createDirectories(configuration.reportDirectory());
            PerformanceReport report = new PerformanceReport(
                1, Instant.now(), System.getProperty("java.version"),
                Bukkit.getVersion(), plugin.getPluginMeta().getVersion(),
                plugin.getServer().getPluginManager().getPlugin("WarSimFrontlineWeapons") == null
                    ? "not-installed" : plugin.getServer().getPluginManager().getPlugin("WarSimFrontlineWeapons").getPluginMeta().getVersion(),
                plugin.getServer().getPluginManager().getPlugin("CraftEngine") == null
                    ? "not-installed" : plugin.getServer().getPluginManager().getPlugin("CraftEngine").getPluginMeta().getVersion(),
                snapshot(Optional.empty()),
                List.of("Codex office-laptop implementation does not include runtime validation.",
                    "Reports do not contain pass/fail, TPS/MSPT, or production capacity conclusions."),
                Map.of("environment", "sanitized")
            );
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC)
                .format(report.generatedAt());
            String base = "warsim-performance-" + stamp;
            Path json = configuration.reportDirectory().resolve(base + ".json");
            Path markdown = configuration.reportDirectory().resolve(base + ".md");
            Files.writeString(json, toJson(report), StandardCharsets.UTF_8);
            Files.writeString(markdown, toMarkdown(report), StandardCharsets.UTF_8);
            rememberReport(json);
            rememberReport(markdown);
            cleanupReports();
            reportsExported.incrementAndGet();
            return new PerformanceExportResult(true, "性能报告已导出。", List.of(json, markdown));
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "[warsim-perf] performance report export failed", exception);
            return new PerformanceExportResult(false, "性能报告导出失败。", List.of());
        }
    }

    @Override
    public void reset() {
        synchronized (lock) {
            windows.clear();
            alerts.clear();
            lastAlertMillis.clear();
        }
        lastSyntheticResult = null;
        lastSampleAt = null;
    }

    @Override
    public void updateMatchContext(UUID matchId, long lifecycleRevision, String matchState) {
        boolean newMatch = this.matchId != null && matchId != null && !this.matchId.equals(matchId);
        this.matchId = matchId;
        this.lifecycleRevision = lifecycleRevision;
        this.matchState = matchState == null ? "UNAVAILABLE" : matchState;
        if (newMatch) {
            synchronized (lock) {
                windows.clear();
            }
        }
    }

    public void updateNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public void updateLoadScenarioReference(String loadScenarioReference) {
        this.loadScenarioReference = loadScenarioReference;
    }

    @Override
    public void close() {
        cancelSynthetic();
        syntheticExecutor.shutdownNow();
        contributors.clear();
        synchronized (lock) {
            windows.clear();
            alerts.clear();
            reports.clear();
            lastAlertMillis.clear();
        }
        state = PerformanceServiceState.CLOSED;
    }

    private void maybeAlert(PerformanceSample sample) {
        long threshold = sample.durationNanos() >= configuration.criticalThresholdNanos()
            ? configuration.criticalThresholdNanos()
            : sample.durationNanos() >= configuration.warningThresholdNanos()
                ? configuration.warningThresholdNanos() : -1;
        if (threshold < 0) return;
        long now = System.currentTimeMillis();
        Long last = lastAlertMillis.get(sample.metricId());
        if (last != null && now - last < configuration.alertCooldownMillis()) return;
        lastAlertMillis.put(sample.metricId(), now);
        PerformanceAlert alert = new PerformanceAlert(
            sample.durationNanos() >= configuration.criticalThresholdNanos()
                ? PerformanceAlertSeverity.CRITICAL : PerformanceAlertSeverity.WARNING,
            sample.metricId(), sample.component(), sample.matchId(),
            sample.lifecycleRevision(), sample.durationNanos(), threshold,
            sample.sampledAt(), sample.context()
        );
        alerts.addLast(alert);
        while (alerts.size() > configuration.maximumAlerts()) alerts.removeFirst();
        alertCount.incrementAndGet();
        logger.warning("[warsim-perf] severity=" + alert.severity()
            + " metricId=" + alert.metricId()
            + " component=" + alert.component()
            + " durationNanos=" + alert.durationNanos()
            + " thresholdNanos=" + alert.thresholdNanos()
            + " matchId=" + alert.matchId()
            + " revision=" + alert.lifecycleRevision());
    }

    private PerformanceMetricsSnapshot serviceMetrics() {
        synchronized (lock) {
            return new PerformanceMetricsSnapshot(
                windows.size(), configuration.maximumMetrics(), totalSamples.get(),
                rejectedSamples.get(), droppedSamples.get(), alertCount.get(),
                reportsExported.get(), syntheticStarted.get(), syntheticCompleted.get(),
                syntheticFailed.get(), syntheticCancelled.get(), lastSampleAt
            );
        }
    }

    private Map<String, String> configurationSummary() {
        return Map.of(
            "enabled", Boolean.toString(configuration.enabled()),
            "samplesPerMetric", Integer.toString(configuration.samplesPerMetric()),
            "maximumMetrics", Integer.toString(configuration.maximumMetrics()),
            "syntheticEnabled", Boolean.toString(configuration.syntheticEnabled()),
            "reportDirectory", configuration.reportDirectory().getFileName().toString()
        );
    }

    private static Map<String, String> safeContext(Map<String, String> input) {
        LinkedHashMap<String, String> safe = new LinkedHashMap<>();
        if (input == null) return safe;
        input.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .limit(16)
            .forEach(entry -> {
                String key = entry.getKey() == null ? "unknown" : entry.getKey().toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9_.-]{1,32}")) return;
                String value = sanitize(entry.getValue());
                if (!value.isBlank()) safe.put(key, value);
            });
        return safe;
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String sanitized = value.replaceAll("[\\p{Cntrl}]", "?");
        sanitized = sanitized.replaceAll("(?i)(password|secret|token|key)=\\S+", "$1=<redacted>");
        return sanitized.length() > 80 ? sanitized.substring(0, 80) : sanitized;
    }

    private void rememberReport(Path path) {
        reports.addLast(path);
        while (reports.size() > configuration.maximumReports() * 2) {
            reports.removeFirst();
        }
    }

    private void cleanupReports() throws IOException {
        try (var stream = Files.list(configuration.reportDirectory())) {
            List<Path> files = stream
                .filter(path -> path.getFileName().toString().matches("warsim-performance-\\d{8}-\\d{6}\\.(json|md)"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
            int extra = files.size() - configuration.maximumReports() * 2;
            for (int index = 0; index < extra; index++) {
                Files.deleteIfExists(files.get(index));
            }
        }
    }

    private static String toJson(PerformanceReport report) {
        StringBuilder builder = new StringBuilder(8192);
        PerformanceSnapshot snapshot = report.snapshot();
        builder.append("{\n")
            .append("  \"schemaVersion\": ").append(report.schemaVersion()).append(",\n")
            .append("  \"generatedAt\": \"").append(report.generatedAt()).append("\",\n")
            .append("  \"javaVersion\": \"").append(escape(report.javaVersion())).append("\",\n")
            .append("  \"paperVersion\": \"").append(escape(report.paperVersion())).append("\",\n")
            .append("  \"warSimVersion\": \"").append(escape(report.warSimVersion())).append("\",\n")
            .append("  \"weaponsVersion\": \"").append(escape(report.weaponsVersion())).append("\",\n")
            .append("  \"craftEngineVersion\": \"").append(escape(report.craftEngineVersion())).append("\",\n")
            .append("  \"nodeId\": \"").append(escape(snapshot.nodeId())).append("\",\n")
            .append("  \"matchId\": \"").append(snapshot.matchId()).append("\",\n")
            .append("  \"lifecycleRevision\": ").append(snapshot.lifecycleRevision()).append(",\n")
            .append("  \"matchState\": \"").append(escape(snapshot.matchState())).append("\",\n")
            .append("  \"loadScenarioReference\": \"").append(escape(snapshot.loadScenarioReference())).append("\",\n")
            .append("  \"metrics\": [\n");
        for (int i = 0; i < snapshot.metrics().size(); i++) {
            PerformanceMetricSnapshot metric = snapshot.metrics().get(i);
            builder.append("    {\"metricId\":\"").append(escape(metric.metricId().value()))
                .append("\",\"component\":\"").append(metric.component())
                .append("\",\"sampleCount\":").append(metric.sampleCount())
                .append(",\"successCount\":").append(metric.successCount())
                .append(",\"failureCount\":").append(metric.failureCount())
                .append(",\"min\":").append(json(metric.minimumNanos()))
                .append(",\"mean\":").append(json(metric.meanNanos()))
                .append(",\"p50\":").append(json(metric.percentiles().p50Nanos()))
                .append(",\"p95\":").append(json(metric.percentiles().p95Nanos()))
                .append(",\"p99\":").append(json(metric.percentiles().p99Nanos()))
                .append(",\"max\":").append(json(metric.maximumNanos()))
                .append("}");
            if (i + 1 < snapshot.metrics().size()) builder.append(",");
            builder.append("\n");
        }
        builder.append("  ],\n  \"alerts\": ").append(snapshot.alerts().size()).append(",\n")
            .append("  \"syntheticResult\": ")
            .append(snapshot.syntheticResult() == null ? "null" : "\"" + escape(snapshot.syntheticResult().scenarioId()) + "\"")
            .append(",\n  \"conclusion\": null\n}");
        return builder.toString();
    }

    private static String toMarkdown(PerformanceReport report) {
        PerformanceSnapshot snapshot = report.snapshot();
        StringBuilder builder = new StringBuilder(8192);
        builder.append("# WarSim Performance Report\n\n")
            .append("- Schema: ").append(report.schemaVersion()).append("\n")
            .append("- Generated: ").append(report.generatedAt()).append("\n")
            .append("- Node: ").append(snapshot.nodeId()).append("\n")
            .append("- Match: ").append(snapshot.matchId()).append(" / revision ")
            .append(snapshot.lifecycleRevision()).append(" / ").append(snapshot.matchState()).append("\n")
            .append("- Load scenario reference: ").append(snapshot.loadScenarioReference()).append("\n\n")
            .append("This report intentionally contains no pass/fail, TPS/MSPT, or production capacity conclusion.\n\n")
            .append("| Metric | Component | Samples | Min | Mean | P50 | P95 | P99 | Max |\n")
            .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (PerformanceMetricSnapshot metric : snapshot.metrics()) {
            builder.append("| ").append(metric.metricId()).append(" | ").append(metric.component())
                .append(" | ").append(metric.sampleCount())
                .append(" | ").append(text(metric.minimumNanos()))
                .append(" | ").append(text(metric.meanNanos()))
                .append(" | ").append(text(metric.percentiles().p50Nanos()))
                .append(" | ").append(text(metric.percentiles().p95Nanos()))
                .append(" | ").append(text(metric.percentiles().p99Nanos()))
                .append(" | ").append(text(metric.maximumNanos()))
                .append(" |\n");
        }
        builder.append("\n## Recent alerts\n\n");
        if (snapshot.alerts().isEmpty()) builder.append("None.\n");
        for (PerformanceAlert alert : snapshot.alerts()) {
            builder.append("- ").append(alert.severity()).append(" ")
                .append(alert.metricId()).append(" durationNanos=")
                .append(alert.durationNanos()).append(" thresholdNanos=")
                .append(alert.thresholdNanos()).append("\n");
        }
        return builder.toString();
    }

    private static String json(java.util.OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : "null";
    }

    private static String text(java.util.OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : "N/A";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
