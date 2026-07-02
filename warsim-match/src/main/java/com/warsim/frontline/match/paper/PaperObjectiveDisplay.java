package com.warsim.frontline.match.paper;

import com.warsim.frontline.api.objective.*;
import com.warsim.frontline.match.objective.DefaultObjectiveService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class PaperObjectiveDisplay implements AutoCloseable {
    private final Map<ObjectiveId, ObjectiveDefinition> definitions = new HashMap<>();
    private final Map<UUID, DisplayEntry> displays = new HashMap<>();
    private DefaultObjectiveService metrics;

    PaperObjectiveDisplay(ObjectiveConfiguration configuration) {
        for (ObjectiveDefinition definition : configuration.definitions()) {
            definitions.put(definition.objectiveId(), definition);
        }
    }

    void attachMetrics(DefaultObjectiveService service) {
        metrics = service;
    }

    void update(ObjectivePresenceFrame frame, List<ObjectiveSnapshot> snapshots) {
        Map<UUID, ObjectivePlayerPresence> players = new HashMap<>();
        for (ObjectivePlayerPresence player : frame.players()) {
            players.put(player.playerUuid(), player);
        }
        List<UUID> stale = displays.keySet().stream()
            .filter(uuid -> !players.containsKey(uuid)).toList();
        stale.forEach(this::remove);
        for (ObjectivePlayerPresence playerPresence : players.values()) {
            ObjectiveSnapshot selected = nearest(playerPresence, snapshots);
            if (selected == null) {
                remove(playerPresence.playerUuid());
            } else {
                show(playerPresence.playerUuid(), selected);
            }
        }
    }

    void remove(UUID playerUuid) {
        DisplayEntry removed = displays.remove(playerUuid);
        if (removed == null) return;
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) player.hideBossBar(removed.bossBar());
        if (metrics != null) metrics.recordDisplayRemoval();
    }

    void clear() {
        for (UUID uuid : List.copyOf(displays.keySet())) remove(uuid);
    }

    private ObjectiveSnapshot nearest(
        ObjectivePlayerPresence player, List<ObjectiveSnapshot> snapshots
    ) {
        ObjectiveSnapshot best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (ObjectiveSnapshot snapshot : snapshots) {
            ObjectiveDefinition definition = definitions.get(snapshot.objectiveId());
            if (definition == null || !definition.region().contains(
                player.worldName(), player.x(), player.y(), player.z()
            )) continue;
            double distance =
                definition.region().horizontalDistanceSquared(player.x(), player.z());
            if (best == null || distance < bestDistance
                || distance == bestDistance
                    && snapshot.objectiveId().compareTo(best.objectiveId()) < 0) {
                best = snapshot;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void show(UUID playerUuid, ObjectiveSnapshot snapshot) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            remove(playerUuid);
            return;
        }
        DisplayState state = DisplayState.from(snapshot);
        DisplayEntry existing = displays.get(playerUuid);
        if (existing != null && existing.state().equals(state)) return;
        if (existing != null && !existing.state().objectiveId().equals(snapshot.objectiveId())) {
            player.hideBossBar(existing.bossBar());
            displays.remove(playerUuid);
            existing = null;
        }
        BossBar bossBar = existing == null ? create(state) : existing.bossBar();
        apply(bossBar, state);
        if (existing == null) player.showBossBar(bossBar);
        displays.put(playerUuid, new DisplayEntry(bossBar, state));
        if (metrics != null) metrics.recordDisplayUpdate();
    }

    private static BossBar create(DisplayState state) {
        return BossBar.bossBar(
            Component.text(state.title()),
            state.progress(),
            state.color(),
            BossBar.Overlay.PROGRESS
        );
    }

    private static void apply(BossBar bossBar, DisplayState state) {
        bossBar.name(Component.text(state.title()));
        bossBar.progress(state.progress());
        bossBar.color(state.color());
    }

    @Override
    public void close() {
        clear();
        definitions.clear();
        metrics = null;
    }

    private record DisplayEntry(BossBar bossBar, DisplayState state) {}

    private record DisplayState(
        ObjectiveId objectiveId,
        String title,
        float progress,
        BossBar.Color color
    ) {
        private static DisplayState from(ObjectiveSnapshot snapshot) {
            String progressing = snapshot.progressingSide() == null
                ? "" : " · 推进：" + snapshot.progressingSide();
            String title = snapshot.displayName() + " · " + snapshot.state()
                + " · " + snapshot.owner() + progressing;
            BossBar.Color color = snapshot.state() == ObjectiveState.CONTESTED
                ? BossBar.Color.YELLOW
                : switch (snapshot.owner()) {
                    case ATTACKERS -> BossBar.Color.BLUE;
                    case DEFENDERS -> BossBar.Color.RED;
                    case NEUTRAL -> BossBar.Color.WHITE;
                };
            return new DisplayState(
                snapshot.objectiveId(), title,
                (float) Math.max(0, Math.min(1, snapshot.progress())), color
            );
        }
    }
}
