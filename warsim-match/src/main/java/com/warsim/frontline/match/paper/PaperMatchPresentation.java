package com.warsim.frontline.match.paper;

import com.warsim.frontline.api.match.MatchConfiguration;
import com.warsim.frontline.api.match.MatchSnapshot;
import com.warsim.frontline.api.match.MatchState;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class PaperMatchPresentation implements AutoCloseable {
    private static final Set<Long> ROUND_ANNOUNCEMENTS =
        Set.of(900L, 300L, 60L, 30L, 10L, 5L, 4L, 3L, 2L, 1L);

    private final MatchConfiguration configuration;
    private final Set<UUID> viewers = new HashSet<>();
    private BossBar bossBar;
    private UUID displayedMatchId;
    private MatchState lastState;
    private long lastSeconds = Long.MIN_VALUE;

    PaperMatchPresentation(MatchConfiguration configuration) {
        this.configuration = configuration;
        this.bossBar = newBossBar();
    }

    void add(Player player) {
        viewers.add(player.getUniqueId());
        player.showBossBar(bossBar);
    }

    void remove(Player player) {
        viewers.remove(player.getUniqueId());
        player.hideBossBar(bossBar);
    }

    void update(MatchSnapshot snapshot, Instant now) {
        if (!snapshot.matchId().equals(displayedMatchId)) {
            replaceBossBar(snapshot.matchId());
        }
        long remaining = remainingSeconds(snapshot, now);
        if (snapshot.state() == lastState && remaining == lastSeconds) {
            return;
        }
        boolean stateChanged = snapshot.state() != lastState;
        lastState = snapshot.state();
        lastSeconds = remaining;
        bossBar.name(Component.text(
            remaining >= 0 ? MatchMessages.countdown(snapshot.state(), remaining)
                : MatchMessages.stateTitle(snapshot.state())
        ));
        bossBar.progress(progress(snapshot, remaining));
        if (stateChanged) {
            broadcastChat(MatchMessages.stateTitle(snapshot.state()));
        }
        if (shouldAnnounce(snapshot.state(), remaining)) {
            broadcastActionBar(MatchMessages.countdown(snapshot.state(), remaining));
        }
    }

    private boolean shouldAnnounce(MatchState state, long seconds) {
        if (!configuration.announcementsEnabled() || seconds < 0) {
            return false;
        }
        return switch (state) {
            case WARMUP -> configuration.warmupAnnouncements().contains((int) seconds);
            case PLAYING -> ROUND_ANNOUNCEMENTS.contains(seconds)
                && seconds <= configuration.roundDurationSeconds();
            default -> false;
        };
    }

    private void replaceBossBar(UUID matchId) {
        BossBar previous = bossBar;
        bossBar = newBossBar();
        displayedMatchId = matchId;
        lastState = null;
        lastSeconds = Long.MIN_VALUE;
        forEachViewer(player -> {
            player.hideBossBar(previous);
            player.showBossBar(bossBar);
        });
    }

    private void broadcastChat(String message) {
        forEachViewer(player -> player.sendMessage(Component.text(message)));
    }

    private void broadcastActionBar(String message) {
        forEachViewer(player -> player.sendActionBar(Component.text(message)));
    }

    private void forEachViewer(java.util.function.Consumer<Player> action) {
        viewers.removeIf(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return true;
            }
            action.accept(player);
            return false;
        });
    }

    private float progress(MatchSnapshot snapshot, long remaining) {
        if (remaining < 0) {
            return 1.0F;
        }
        long total = switch (snapshot.state()) {
            case WARMUP -> configuration.warmupSeconds();
            case PLAYING -> configuration.roundDurationSeconds();
            case ENDING -> configuration.endingSeconds();
            default -> 1;
        };
        return Math.max(0.0F, Math.min(1.0F, remaining / (float) total));
    }

    private long remainingSeconds(MatchSnapshot snapshot, Instant now) {
        Instant deadline = switch (snapshot.state()) {
            case WARMUP -> snapshot.scheduledStartAt();
            case PLAYING -> snapshot.scheduledEndAt();
            case ENDING -> snapshot.stateEnteredAt().plusSeconds(configuration.endingSeconds());
            default -> null;
        };
        if (deadline == null) {
            return -1;
        }
        return Math.max(0, (Duration.between(now, deadline).toMillis() + 999) / 1000);
    }

    private static BossBar newBossBar() {
        return BossBar.bossBar(
            Component.text("等待玩家加入"),
            1.0F,
            BossBar.Color.BLUE,
            BossBar.Overlay.PROGRESS
        );
    }

    @Override
    public void close() {
        forEachViewer(player -> player.hideBossBar(bossBar));
        viewers.clear();
    }
}
