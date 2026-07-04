# T-018 Vehicles

T-018 is the first vehicle lifecycle foundation for WarSim: Frontline.

It adds platform-neutral vehicle definitions in `warsim-vehicles` and a Paper
runtime coordinator in the main `WarSimFrontline` plugin. The Paper runtime owns
managed fallback anchors, command diagnostics, seat ownership, and cleanup on
Match lifecycle changes. It does not modify `DefaultMatchService`.

## Scope

- Two bundled placeholder definitions: `truck_m1918` and `scout_car_m1917`.
- `/warsim vehicle status|definitions|spawn|list|inspect|despawn|move`.
- Fallback Bukkit `ArmorStand` anchors when ModelEngine is not available.
- ModelEngine is detected only as a plugin presence signal in this base version.
- Active vehicles are despawned on configured ENDING, RESETTING, FAILED/STOPPED
  transitions and plugin shutdown.

## Not Included

T-018 does not add vehicle weapons, damage, armor, projectiles, explosions,
passenger mounting, fuel, repair, persistence, real player input physics, or
final ModelEngine/MythicMobs assets.

## Configuration

The `vehicles` section controls whether the subsystem is enabled, ModelEngine
fail-closed behavior, runtime limits, cleanup rules, default movement values,
and vehicle definitions.

`anchor-entity-type` is limited to `ARMOR_STAND` in T-018. Spawn commands only
use already-loaded worlds and chunks; the vehicle subsystem does not create,
load, unload, copy, or delete worlds.

## Runtime Safety

All managed anchors are tagged with WarSim-owned PDC keys. The runtime cancels
damage to those anchors, removes its own anchors during lifecycle cleanup, and
does not clear player inventory, XP, EnderChest, or world files.

Seat ownership is diagnostic only. Right-clicking a managed anchor claims or
releases the driver seat, but this base version does not mount the player or
drive from live keyboard input.
