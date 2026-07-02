package com.warsim.frontline.match.loadtest;

import com.warsim.frontline.api.loadtest.*;
import com.warsim.frontline.api.roster.SquadId;
import com.warsim.frontline.api.roster.TeamSide;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.FileConfiguration;

final class LoadScenarioTemplateFactory {
    static final String DEFAULT_NOTICE = "模板坐标，部署到真实负载测试地图前必须由管理员确认或调整";
    private static final List<String> ZONE_ORDER = List.of(
        "spawn_attackers", "spawn_defenders", "objective_dense", "objective_multi",
        "weapon_close", "weapon_medium", "weapon_long", "weapon_blocked",
        "mixed_battle", "idle_control"
    );

    LoadMapDefinition map(FileConfiguration yaml) {
        String id = yaml.getString("map.id", "warsim_load_test");
        String version = yaml.getString("map.version", "1.0.0");
        String world = yaml.getString("map.world", "warsim_load_test");
        String notice = yaml.getString("map.coordinate-notice", DEFAULT_NOTICE);
        List<LoadZoneDefinition> zones = new ArrayList<>();
        for (String zoneId : ZONE_ORDER) {
            zones.add(zone(yaml, world, zoneId));
        }
        return new LoadMapDefinition(
            new LoadMapId(id), new LoadMapVersion(version), world, notice, zones
        );
    }

    List<LoadScenarioDefinition> scenarios(FileConfiguration yaml, LoadMapDefinition map) {
        Map<String, LoadScenarioType> types = Map.ofEntries(
            Map.entry("roster_100", LoadScenarioType.ROSTER),
            Map.entry("objective_100_single", LoadScenarioType.OBJECTIVE_SINGLE),
            Map.entry("objective_100_multi", LoadScenarioType.OBJECTIVE_MULTI),
            Map.entry("weapon_100_close", LoadScenarioType.WEAPON_CLOSE),
            Map.entry("weapon_100_medium", LoadScenarioType.WEAPON_MEDIUM),
            Map.entry("weapon_100_long", LoadScenarioType.WEAPON_LONG),
            Map.entry("weapon_100_blocked", LoadScenarioType.WEAPON_BLOCKED),
            Map.entry("mixed_100", LoadScenarioType.MIXED),
            Map.entry("idle_baseline", LoadScenarioType.IDLE),
            Map.entry("cleanup_validation", LoadScenarioType.CLEANUP)
        );
        List<LoadScenarioDefinition> scenarios = new ArrayList<>();
        for (var entry : types.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String id = entry.getKey();
            LoadScenarioId scenarioId = new LoadScenarioId(id);
            List<String> zones = zonesFor(entry.getValue());
            List<LoadSpawnDefinition> slots = entry.getValue() == LoadScenarioType.IDLE
                ? List.of() : slotsFor(scenarioId, entry.getValue(), map.worldName());
            List<LoadLaneDefinition> lanes = lanesFor(entry.getValue(), map.worldName());
            scenarios.add(new LoadScenarioDefinition(
                scenarioId,
                entry.getValue(),
                yaml.getInt("schema-version", 1),
                map.mapId(),
                map.version(),
                yaml.getString("scenarios." + id + ".version", "1.0.0"),
                yaml.getString("scenarios." + id + ".display-name", id),
                zones,
                slots,
                lanes,
                yaml.getString("scenarios." + id + ".description", DEFAULT_NOTICE)
            ));
        }
        return scenarios.stream()
            .sorted(Comparator.comparing(value -> value.scenarioId().value()))
            .toList();
    }

    private LoadZoneDefinition zone(FileConfiguration yaml, String world, String zoneId) {
        String base = "zones." + zoneId + ".";
        double cx = yaml.getDouble(base + "center.x", defaultCenterX(zoneId));
        double cy = yaml.getDouble(base + "center.y", 80.0);
        double cz = yaml.getDouble(base + "center.z", defaultCenterZ(zoneId));
        double rx = yaml.getDouble(base + "radius.x", defaultRadiusX(zoneId));
        double ry = yaml.getDouble(base + "radius.y", 8.0);
        double rz = yaml.getDouble(base + "radius.z", defaultRadiusZ(zoneId));
        return new LoadZoneDefinition(
            zoneId,
            yaml.getString(base + "world", world),
            new LoadCoordinate(world, cx, cy, cz, 0.0f, 0.0f),
            cx - rx, cy - ry, cz - rz,
            cx + rx, cy + ry, cz + rz,
            yaml.getStringList(base + "purposes").isEmpty()
                ? List.of(zoneId) : yaml.getStringList(base + "purposes"),
            yaml.getInt(base + "maximum-participants", zoneId.equals("idle_control") ? 0 : 100),
            yaml.getString(base + "scenario-id", zoneId),
            yaml.getString(base + "version", "1.0.0"),
            yaml.getString(base + "description", DEFAULT_NOTICE)
        );
    }

    private List<LoadSpawnDefinition> slotsFor(
        LoadScenarioId scenarioId, LoadScenarioType type, String world
    ) {
        return switch (type) {
            case ROSTER -> rosterSlots(scenarioId, world);
            case OBJECTIVE_SINGLE -> ringSlots(scenarioId, world, 0, 80, 0, 10.0);
            case OBJECTIVE_MULTI -> gridSlots(scenarioId, world, -40, 80, 40, 8, 8);
            case WEAPON_CLOSE -> facingSlots(scenarioId, world, -40, 80, -80, 18.0);
            case WEAPON_MEDIUM -> facingSlots(scenarioId, world, 0, 80, -140, 45.0);
            case WEAPON_LONG -> facingSlots(scenarioId, world, 60, 80, -220, 90.0);
            case WEAPON_BLOCKED -> facingSlots(scenarioId, world, 130, 80, -80, 35.0);
            case MIXED -> gridSlots(scenarioId, world, 0, 80, 130, 10, 6);
            default -> gridSlots(scenarioId, world, -80, 80, 0, 6, 6);
        };
    }

    private List<LoadSpawnDefinition> rosterSlots(LoadScenarioId scenarioId, String world) {
        List<LoadSpawnDefinition> slots = new ArrayList<>(100);
        SquadId[] squads = SquadId.values();
        for (TeamSide side : TeamSide.values()) {
            int teamOffset = side == TeamSide.ATTACKERS ? 0 : 50;
            double centerX = side == TeamSide.ATTACKERS ? -80 : 80;
            for (int i = 0; i < 50; i++) {
                int row = i / 10;
                int column = i % 10;
                slots.add(slot(
                    scenarioId, world, side, squads[i / 5], teamOffset + i,
                    centerX + (column - 4.5) * 5.0, 80, (row - 2.0) * 5.0,
                    side == TeamSide.ATTACKERS ? 90.0f : -90.0f
                ));
            }
        }
        return slots;
    }

    private List<LoadSpawnDefinition> gridSlots(
        LoadScenarioId scenarioId, String world, double startX, double y, double startZ,
        double spacingX, double spacingZ
    ) {
        List<LoadSpawnDefinition> slots = new ArrayList<>(100);
        SquadId[] squads = SquadId.values();
        for (TeamSide side : TeamSide.values()) {
            int teamOffset = side == TeamSide.ATTACKERS ? 0 : 50;
            double sideZ = startZ + (side == TeamSide.ATTACKERS ? -spacingZ * 3 : spacingZ * 3);
            for (int i = 0; i < 50; i++) {
                SquadId squad = squads[i / 5];
                int row = i / 10;
                int column = i % 10;
                slots.add(slot(
                    scenarioId, world, side, squad, teamOffset + i,
                    startX + (column - 4.5) * spacingX, y, sideZ + row * spacingZ,
                    side == TeamSide.ATTACKERS ? 0.0f : 180.0f
                ));
            }
        }
        return slots;
    }

    private List<LoadSpawnDefinition> ringSlots(
        LoadScenarioId scenarioId, String world, double cx, double y, double cz, double radius
    ) {
        List<LoadSpawnDefinition> slots = new ArrayList<>(100);
        SquadId[] squads = SquadId.values();
        for (TeamSide side : TeamSide.values()) {
            int teamOffset = side == TeamSide.ATTACKERS ? 0 : 50;
            double sideRadius = radius + (side == TeamSide.ATTACKERS ? 0.0 : 4.0);
            for (int i = 0; i < 50; i++) {
                double angle = (Math.PI * 2.0 * i) / 50.0;
                slots.add(slot(
                    scenarioId, world, side, squads[i / 5], teamOffset + i,
                    cx + Math.cos(angle) * sideRadius, y, cz + Math.sin(angle) * sideRadius,
                    (float) Math.toDegrees(angle + Math.PI)
                ));
            }
        }
        return slots;
    }

    private List<LoadSpawnDefinition> facingSlots(
        LoadScenarioId scenarioId, String world, double x, double y, double z, double distance
    ) {
        List<LoadSpawnDefinition> slots = new ArrayList<>(100);
        SquadId[] squads = SquadId.values();
        for (TeamSide side : TeamSide.values()) {
            int teamOffset = side == TeamSide.ATTACKERS ? 0 : 50;
            double sideX = x + (side == TeamSide.ATTACKERS ? -distance / 2.0 : distance / 2.0);
            for (int i = 0; i < 50; i++) {
                slots.add(slot(
                    scenarioId, world, side, squads[i / 5], teamOffset + i,
                    sideX, y, z + (i - 24.5) * 1.2,
                    side == TeamSide.ATTACKERS ? -90.0f : 90.0f
                ));
            }
        }
        return slots;
    }

    private LoadSpawnDefinition slot(
        LoadScenarioId scenarioId, String world, TeamSide side, SquadId squad,
        int index, double x, double y, double z, float yaw
    ) {
        String prefix = side == TeamSide.ATTACKERS ? "attacker" : "defender";
        return new LoadSpawnDefinition(
            prefix + "_" + String.format("%02d", index % 50 + 1),
            side,
            squad,
            new LoadCoordinate(world, x, y, z, yaw, 0.0f),
            scenarioId
        );
    }

    private List<LoadLaneDefinition> lanesFor(LoadScenarioType type, String world) {
        return switch (type) {
            case WEAPON_CLOSE -> List.of(lane("close_lane", world, -49, 80, -80, -31, 80, -80, 18, null));
            case WEAPON_MEDIUM -> List.of(lane("medium_lane", world, -22.5, 80, -140, 22.5, 80, -140, 45, null));
            case WEAPON_LONG -> List.of(lane("long_lane", world, 15, 80, -220, 105, 80, -220, 90, null));
            case WEAPON_BLOCKED -> List.of(lane("blocked_lane", world, 112.5, 80, -80, 147.5, 80, -80, 35,
                new LoadCoordinate(world, 130, 80, -80, 0, 0)));
            case MIXED -> List.of(lane("mixed_weapon_lane", world, -10, 80, 130, 25, 80, 130, 35, null));
            default -> List.of();
        };
    }

    private LoadLaneDefinition lane(
        String id, String world, double sx, double sy, double sz,
        double ex, double ey, double ez, double distance, LoadCoordinate blocker
    ) {
        return new LoadLaneDefinition(
            id,
            new LoadCoordinate(world, sx, sy, sz, 0, 0),
            new LoadCoordinate(world, ex, ey, ez, 0, 0),
            blocker,
            distance,
            DEFAULT_NOTICE
        );
    }

    private List<String> zonesFor(LoadScenarioType type) {
        return switch (type) {
            case ROSTER -> List.of("spawn_attackers", "spawn_defenders");
            case OBJECTIVE_SINGLE -> List.of("objective_dense");
            case OBJECTIVE_MULTI -> List.of("objective_multi");
            case WEAPON_CLOSE -> List.of("weapon_close");
            case WEAPON_MEDIUM -> List.of("weapon_medium");
            case WEAPON_LONG -> List.of("weapon_long");
            case WEAPON_BLOCKED -> List.of("weapon_blocked");
            case MIXED -> List.of("mixed_battle");
            case IDLE -> List.of("idle_control");
            case CLEANUP -> List.of("spawn_attackers", "spawn_defenders", "mixed_battle");
        };
    }

    private double defaultCenterX(String zoneId) {
        return switch (zoneId) {
            case "spawn_attackers" -> -80;
            case "spawn_defenders" -> 80;
            case "weapon_close" -> -40;
            case "weapon_medium" -> 0;
            case "weapon_long" -> 60;
            case "weapon_blocked" -> 130;
            default -> 0;
        };
    }

    private double defaultCenterZ(String zoneId) {
        return switch (zoneId) {
            case "objective_multi" -> 40;
            case "weapon_close" -> -80;
            case "weapon_medium" -> -140;
            case "weapon_long" -> -220;
            case "weapon_blocked" -> -80;
            case "mixed_battle" -> 130;
            case "idle_control" -> 220;
            default -> 0;
        };
    }

    private double defaultRadiusX(String zoneId) {
        return switch (zoneId) {
            case "weapon_long" -> 70;
            case "weapon_medium", "weapon_blocked", "weapon_close" -> 45;
            case "objective_dense" -> 25;
            default -> 90;
        };
    }

    private double defaultRadiusZ(String zoneId) {
        return switch (zoneId) {
            case "weapon_long", "weapon_medium", "weapon_blocked", "weapon_close" -> 40;
            case "objective_dense" -> 25;
            default -> 90;
        };
    }
}
