# Vehicles

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

## T-019 Damage Core

T-019 adds server-authoritative health to each managed vehicle. The domain
types live in `warsim-vehicles` and remain platform-neutral: damage requests,
damage results, health configuration, health snapshots, and combat snapshots do
not reference Bukkit, Paper, ModelEngine, or MythicMobs.

Vehicle combat is configured under `vehicles.combat`. The bundled placeholders
use `truck_m1918` at 450 health and `scout_car_m1917` at 300 health. Damage is
multiplied by type (`ADMIN`, `SMALL_ARMS`, `IMPACT`, `ENVIRONMENT`, `SCRIPTED`,
`UNKNOWN`) before reducing health. Health at or below zero marks the vehicle as
`DESTROYED`, clears driver ownership, and either schedules despawn or leaves the
current fallback anchor as an inert wreck placeholder when `leave-wreck=true`.

Admins can use:

- `/warsim vehicle combat`
- `/warsim vehicle damage <runtimeId> <amount> [type]`
- `/warsim vehicle repair <runtimeId> <amount|full>`
- `/warsim vehicle destroy <runtimeId>`

Managed ArmorStand anchors cancel vanilla Bukkit damage. Player or projectile
damage against an anchor is converted conservatively to vehicle `SMALL_ARMS`
damage; other causes are cancelled without letting the ArmorStand die directly.

T-019 intentionally does not modify `warsim-weapons-paper`. The current weapon
hitscan path samples online players and applies damage through a player-only
adapter, so vehicle-hit integration is left for a later explicit pass rather
than bending the weapon pipeline in this phase.

Still not included: cannons, machine guns, vehicle weapons, projectiles,
explosions, armor, penetration, passenger damage, repair tools, ticket scoring,
database persistence, and final ModelEngine visuals.

## T-020 Weapon Soft Integration

T-020 adds a shared, platform-neutral `VehicleDamageService` in `warsim-api`.
The main Paper plugin registers the service from `PaperVehicleCoordinator`;
Weapons looks it up optionally through Bukkit `ServicesManager`.

When a Weapons hitscan ray hits a managed vehicle anchor, the Weapons plugin
applies `SMALL_ARMS` vehicle damage through the service. Missing service means
the old weapon behavior is preserved. Vehicle inspect and combat diagnostics
show the last attacker, weapon id, source description, damage type, amount, and
time.

This integration does not write tickets, scores, database progression, block
ledgers, or resource-pack state. It still does not add cannons, projectiles,
explosions, armor, penetration, passenger damage, vehicle-mounted weapons, or
final ModelEngine visuals.
