# T-010 Load Test Map and Repeatable Scenarios

T-010 defines stable load-test map and scenario metadata for later server-side pressure testing.

Current Codex implementation status: functionality is implemented and compile-checked only. No tests, Paper runtime validation, synthetic execution, or pressure scenario was run on the office laptop.

## Scope

The load map subsystem is diagnostic and preparatory only.

It implements:

- platform-neutral load map and scenario DTOs;
- fixed map ID and version metadata;
- ten stable zones;
- ten stable scenarios;
- deterministic 100-slot layouts;
- 50v50 and 10 squads × 5 slots per team;
- read-only validation;
- in-memory prepare context;
- idempotent clean;
- `/warsim loadmap ...` commands.

It does not:

- create the `warsim_load_test` world;
- copy or download world folders;
- place or delete blocks;
- call WorldEdit;
- teleport players;
- create fake players, NPCs, ArmorStands, robots, or entities;
- start or modify the production Match;
- run T-009 synthetic load;
- export pressure reports;
- output performance conclusions.

## World

Default world name:

```text
warsim_load_test
```

If the world is missing, the service reports `UNLOADED` or validation failure. It never creates the world automatically.

All packaged coordinates are:

```text
模板坐标，部署到真实负载测试地图前必须由管理员确认或调整
```

## Fixed zones

- `spawn_attackers`
- `spawn_defenders`
- `objective_dense`
- `objective_multi`
- `weapon_close`
- `weapon_medium`
- `weapon_long`
- `weapon_blocked`
- `mixed_battle`
- `idle_control`

Each zone has a world, center, min/max bounds, purposes, maximum suggested participants, stable scenario reference, version, and description.

## Fixed scenarios

- `roster_100`
- `objective_100_single`
- `objective_100_multi`
- `weapon_100_close`
- `weapon_100_medium`
- `weapon_100_long`
- `weapon_100_blocked`
- `mixed_100`
- `idle_baseline`
- `cleanup_validation`

Scenario snapshots are immutable and stable enough to be referenced by future T-012 pressure reports.

## Slot distribution

For scenarios with participants, the template creates deterministic slots:

- 50 ATTACKERS;
- 50 DEFENDERS;
- ALPHA through JULIET for each side;
- exactly five slots per squad;
- fixed world/x/y/z/yaw/pitch;
- stable slot ID and scenario ID.

Layouts include grid, ring, opposing-line, objective-dense, objective-multi, weapon lanes, and mixed battle placement. These are definitions only; no real player is moved.

## Validation

Validation checks:

- world existence;
- finite coordinates;
- legal zone bounds;
- unique zone IDs;
- unique scenario IDs;
- unique slot IDs;
- slot world and zone containment;
- 50v50 slot counts where applicable;
- 10×5 squad distribution per side;
- lane start/end world;
- blocked weapon lane blocker definition;
- map/scenario versions;
- safe coordinate boundaries.

Validation failure affects only the LoadMap subsystem. It must not shut down Match, Roster, Objective, Ticket, Weapons, database, Redis, Velocity, or the main plugin.

## State machine

- `DISABLED`
- `UNLOADED`
- `VALIDATING`
- `READY`
- `PREPARED`
- `DIRTY`
- `CLEANING`
- `FAILED`
- `CLOSED`

`prepare` is rejected while the production Match is `PLAYING`. Only one scenario may be prepared at a time.

## Commands

All commands are under `/warsim`; no `/loadmap` command is registered.

- `/warsim loadmap status`
- `/warsim loadmap list`
- `/warsim loadmap validate [mapId]`
- `/warsim loadmap scenarios`
- `/warsim loadmap scenario <scenarioId>`
- `/warsim loadmap prepare <scenarioId>`
- `/warsim loadmap clean`
- `/warsim loadmap snapshot`

## Configuration files

Packaged defaults:

- `config/load-maps.yml`
- `config/load-scenarios.yml`

Development examples:

- `dev-environment/load-test-map/load-maps.yml.example`
- `dev-environment/load-test-map/load-scenarios.yml.example`

Automatic deployment scripts and server operations templates are explicitly out of scope for the current WarSim plugin roadmap. T-010 deliberately does not perform deployment, and T-011 continues with gameplay features only.
