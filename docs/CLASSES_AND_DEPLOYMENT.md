# Classes and Deployment

T-011 adds the first WarSim: Frontline class and deployment layer. It is gameplay plugin functionality only; it does not create deployment scripts, server folders, service units, PID files, RCON automation, or any operations system.

## Combat class model

Combat classes use `CombatClassId` as an extensible value object, not a closed enum. Built-in defaults are `assault`, `medic`, `support`, and `scout`. IDs must match `[a-z0-9_-]{1,32}`.

## Selection and combat state

`PreferredClassSelection` belongs to the local session or node connection lifetime. `PlayerClassSelection` belongs to one `matchId` and is recreated for every new match. Old current class, pending class, combat state, deployment revision, life revision, countdown, and ticket records are not inherited into a new match.

`PlayerCombatState` is fixed to `NOT_DEPLOYED`, `ALIVE`, `DEAD`, `WAITING_DEPLOYMENT`, `DEPLOYING`, and `CLOSED`. Only `ALIVE` players are valid combat participants for weapons, Objective Presence, WarSim combat damage, hit target sampling, and kill attribution.

## Deployment transactions

Deployment records both `DeploymentReason` (`INITIAL_DEPLOYMENT` or `RESPAWN`) and `DeploymentTrigger` (`MANUAL` or `ADMIN_FORCE`). Admin force only skips the countdown; it does not bypass match state, participant state, roster assignment, class limits, ticket cost, spawn safety, or loadout provider requirements.

Every committed deployment creates a new `lifeRevision`. Weapons, reload state, cooldowns, ActionBar state, damage attribution, managed loadout records, death handling, and delayed combat events must be bound to the active life revision.

The transaction validates match, participant, roster, class limits, provider availability, loadout token, safe spawn, and tickets before teleporting and granting equipment. `ALIVE` is committed only after teleport, loadout grant, and health restoration. If a post-ticket step fails, the system attempts an idempotent refund and returns the player to a non-combat waiting state.

## Loadout provisioning

The main Paper plugin owns the platform-neutral `CombatLoadoutProvisioningService` contract. The independent Weapons plugin implements it through Bukkit `ServicesManager`. The contract passes only UUIDs, match IDs, revisions, `WeaponId`, CraftEngine namespaced item IDs, and immutable DTOs; it does not expose Bukkit `Player`, `ItemStack`, CraftEngine adapters, or the Weapons plugin entry class.

## Tickets and spawns

Initial deployment is free by default. Respawn ticket costs are configured separately. The only T-011 respawn ticket charge point is the deployment transaction after loadout preparation and before teleport; death events do not charge tickets.

T-011 implements only fixed team spawns. Reserved squad, objective, and vehicle spawn types are public API placeholders and return unavailable/unimplemented results at this stage. Safe spawn search runs on the Paper main thread, does not load chunks, and checks a finite deterministic set of loaded candidate positions.

## Commands

All commands remain under `/warsim`:

- `/warsim class status`
- `/warsim class list`
- `/warsim class select <classId>`
- `/warsim deploy`
- `/warsim deploy cancel`
- `/warsim class status <player>`
- `/warsim class set <player> <classId>`
- `/warsim class clear <player>`
- `/warsim deployment status`
- `/warsim deployment spawn list`
- `/warsim deployment force <player>`

No top-level `/class`, `/deploy`, or `/respawn` command is registered.
