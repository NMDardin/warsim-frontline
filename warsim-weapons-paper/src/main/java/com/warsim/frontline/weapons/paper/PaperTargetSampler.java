package com.warsim.frontline.weapons.paper;

import com.warsim.frontline.api.battle.WarSimBattleRuntime;
import com.warsim.frontline.api.weapon.*;
import com.warsim.frontline.weapons.DefaultWeaponService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

final class PaperTargetSampler {
    private final WarSimBattleRuntime runtime;
    private final WeaponConfiguration configuration;
    private final DefaultWeaponService service;

    PaperTargetSampler(
        WarSimBattleRuntime runtime, WeaponConfiguration configuration,
        DefaultWeaponService service
    ) {
        this.runtime = runtime;
        this.configuration = configuration;
        this.service = service;
    }

    List<HitCandidate> sample(Player shooter, UUID matchId, double maximumRange) {
        Location origin = shooter.getEyeLocation();
        double boundary = maximumRange + 2;
        double boundarySquared = boundary * boundary;
        ArrayList<Sample> samples = new ArrayList<>();
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(shooter.getUniqueId())
                || target.getWorld() != shooter.getWorld()) continue;
            var status = runtime.player(target.getUniqueId());
            if (status.isEmpty() || !status.get().activeFor(matchId)) continue;
            Location location = target.getLocation();
            double distanceSquared = location.distanceSquared(origin);
            if (distanceSquared > boundarySquared) continue;
            BoundingBox box = target.getBoundingBox();
            double headStart = box.getMaxY()
                - Math.max(0.01, box.getHeight() * configuration.headHeightRatio());
            AxisAlignedBox head = new AxisAlignedBox(
                box.getMinX(), headStart, box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ()
            );
            AxisAlignedBox body = new AxisAlignedBox(
                box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), headStart, box.getMaxZ()
            );
            samples.add(new Sample(
                distanceSquared,
                new HitCandidate(
                    target.getUniqueId(), matchId, target.getWorld().getName(), body, head
                )
            ));
        }
        samples.sort(Comparator.comparingDouble(Sample::distanceSquared)
            .thenComparing(value -> value.candidate().targetUuid().toString()));
        if (samples.size() > configuration.maximumCandidatesPerShot()) {
            service.recordCandidateTruncation();
            samples.subList(configuration.maximumCandidatesPerShot(), samples.size()).clear();
        }
        return samples.stream().map(Sample::candidate).toList();
    }

    private record Sample(double distanceSquared, HitCandidate candidate) {}
}
