# Round Reset

T-013 adds a Paper-side round reset transaction for the official battle node. It clears only transient round state after the Match enters `RESETTING`.

It does not restore blocks, copy a world folder, delete a world folder, unload worlds, reload worlds, or create worlds. Block change logging and block restoration belong to the later map restoration work.

## Configuration

The reset configuration lives under `match.reset`:

```yaml
match:
  reset:
    enabled: true
    start-delay-ticks: 1
    evacuate-online-players: true
    maximum-transient-entities: 10000
    transient-worlds:
      - "world"
    holding-spawn:
      world: "world"
      x: 0.5
      y: 100.0
      z: 0.5
      yaw: 0.0
      pitch: 0.0
```

Official battle nodes require reset to be enabled when Match is enabled. Lobby nodes may keep Match and reset disabled.

Before deploying a real map, confirm `holding-spawn` and `transient-worlds`. Do not configure a shared lobby world as a transient battle cleanup world.

## Transaction Order

`DefaultMatchService` remains the owner of Match state. When it enters `RESETTING`, `PaperMatchCoordinator` publishes the Battle Runtime lifecycle event before the Paper reset service performs cleanup. Class, combat, and Weapons listeners therefore receive `RESETTING` first.

The reset service then runs one delayed main-thread Bukkit task. It returns `MatchResetResult.success(...)` only when every configured cleanup step succeeds. Any failure returns `MatchResetResult.failure(...)`; `DefaultMatchService` decides whether the Match enters `FAILED`.

Duplicate reset requests for the same Match and lifecycle revision share the same active result or return the cached completed result. A different reset request while one is active is rejected.

## Player Evacuation

When enabled, only online players with a local WarSim battle session are evacuated. Each target player has open inventory closed, leaves vehicles, is placed in spectator mode, has fire, fall distance, velocity, and active potion effects cleared, then is teleported to the configured holding spawn.

Reset does not clear player inventory, XP, EnderChest, advancements, database data, or cross-server state.

## Transient Entities

Only these entity families are removed from configured transient worlds:

- `Item`
- `ExperienceOrb`
- `Projectile`
- `AreaEffectCloud`
- `TNTPrimed`
- `FallingBlock`
- `Firework`

Players, normal living entities, armor stands, display entities, interactions, item frames, paintings, markers, model entities, and unknown plugin entities are retained.

The service collects candidates first. If the count is above `maximum-transient-entities`, it fails without partial deletion.

## Diagnostics

Use `/warsim match status`. The Round Reset section reports whether reset is enabled, whether a reset is active, the latest Match and revision, latest result and duration, cumulative attempts, successes and failures, last evacuated player count, last removed transient entity count, and the latest sanitized error summary.
