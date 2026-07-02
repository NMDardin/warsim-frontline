# T-012 Combat Outcomes, Spawn Protection and HUD

T-012 adds current-match combat feedback only. It does not persist career data,
leaderboards, experience, currency, achievements, or ticket penalties.

## Match-local statistics

- Statistics belong only to the current `matchId`.
- A new match creates a fresh combat context.
- Player identities are UUID based; display names are snapshots for UI only.
- The system tracks kills, deaths, assists, headshot kills, team kills, suicides,
  environmental deaths, damage dealt/received, longest kill distance and streaks.
- Death settlement is guarded by `matchId + playerUuid + lifeRevision`, so one
  life can be settled at most once.

## Damage contribution

Weapon damage uses a two-stage correlation:

1. Weapons begin a short-lived bounded correlation before applying Paper damage.
2. The correlation is completed only after actual effective survivability loss is
   observed.
3. Cancelled, protected, stale, friendly-blocked or zero-effective-damage hits are
   cancelled and do not create contributions.

Contribution history is bounded per target life. Assist eligibility uses minimum
damage, minimum percentage, TTL, current-match membership and life-revision checks.

## Death settlement

`CombatOutcomeCoordinator` is the only authoritative `PlayerDeathEvent` entry for
combat settlement. When CombatOutcome is disabled or failed, a downgrade path only
calls the T-011 death transition and does not create statistics or KillFeed output.

T-012 never deducts respawn tickets. Ticket costs remain owned by the T-011
deployment transaction.

## Spawn protection

Spawn protection is created after a T-011 deployment transaction commits. It is
bound to match, lifecycle revision, life revision, deployment revision and a
platform-neutral spawn position snapshot.

By default it blocks WarSim combat damage from current-match players, not void,
world-border or administrator-forced damage. The protection expires on time, death,
quit, reset, class clear, new deployment, attack, movement out of radius, or
objective-presence removal when enabled.

Protected players are excluded from Objective Presence. If objective removal is
enabled, entering a region removes protection but the current scan still does not
count that player.

## ActionBar arbitration

`PlayerFeedbackService` is the single ActionBar arbitration point. Priorities are:

1. CRITICAL
2. DEPLOYMENT
3. COMBAT
4. WEAPON
5. OBJECTIVE
6. SYSTEM

Weapons use the shared service when available and safely fall back to their local
display path only when the service is unavailable.

## HUD and KillFeed

The WarSim HUD is a managed Sidebar. It does not overwrite a foreign Sidebar by
default; if another plugin owns the current scoreboard, HUD state becomes
`BLOCKED_BY_FOREIGN_SCOREBOARD`.

KillFeed is bounded and uses sanitized display-name snapshots. It is not an
identity system and is not persisted. Chat output is throttled to avoid spam.

## Validation status

This feature has only been implemented and compiled with tests skipped in the
office workstation environment. Server startup, client visuals, CraftEngine,
Redis, PostgreSQL and real combat verification remain manual server-environment
tasks.
