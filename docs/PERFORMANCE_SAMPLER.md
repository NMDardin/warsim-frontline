# T-009 Performance Sampler and Synthetic Load

T-009 adds a local, bounded performance sampler owned by the main WarSim Paper plugin.

Current Codex implementation status: functionality is implemented and compile-checked only. No unit tests, integration tests, Paper runtime validation, CraftEngine validation, PostgreSQL/Redis validation, synthetic execution, or pressure test was run on the office laptop.

## Service boundary

- `WarSimFrontline` owns the single mutable `PerformanceService`.
- External plugins, including the independent Weapons plugin, may register read-only `PerformanceContributor` instances.
- The main plugin does not depend on `warsim-weapons-paper`, CraftEngine adapters, or the Weapons plugin entrypoint.
- Contributors must unregister during plugin shutdown.

## Bounded metrics

Each metric uses a fixed-capacity ring window. The service also limits:

- maximum metric windows;
- recent alert history;
- retained report records;
- synthetic task concurrency.

Disabled service paths return `NoOpPerformanceSpan`. A span can complete only once, so repeated `success()` or `failure()` calls do not duplicate samples.

## Percentiles

Snapshots calculate percentiles from the current bounded window with a nearest-rank strategy:

```text
rank = ceil(percentile * sampleCount)
index = clamp(rank - 1, 0, sampleCount - 1)
```

Percentiles are optional. When insufficient samples exist, reports show `N/A`; zero is not used as a fake percentile value.

## Slow operation alerts

The sampler supports warning and critical thresholds, per-metric log cooldown, bounded alert history, matchId, lifecycle revision, component, metricId, and controlled context labels.

## Synthetic load

Synthetic execution is implemented but disabled by default:

```yaml
performance:
  synthetic:
    enabled: false
```

Implemented scenario families:

- `ROSTER_100_PLAYER_ASSIGNMENT`
- `OBJECTIVE_100_PLAYER_PRESENCE`
- `WEAPON_100_SHOOTERS_100_CANDIDATES`
- `MIXED_BATTLE_TICK`

Synthetic execution is pure Java and must not mutate the live Match, Roster, Objective, Ticket, Weapons, Redis, or database state. It does not create players, NPCs, entities, ArmorStands, projectiles, or Bukkit tasks per sample.

Dry-run remains available for planning only:

```text
/warsim perf dryrun <scenarioId>
```

Actual execution is available only when enabled by server administrators:

```text
/warsim perf synthetic <scenarioId> [iterations]
/warsim perf synthetic status
/warsim perf synthetic cancel
```

Codex did not run these commands and did not produce real performance data.

## Report export

`/warsim perf export` writes JSON and Markdown reports using UTF-8 and safe filenames. Reports include:

- schema version;
- generated timestamp;
- Java, Paper, WarSim, Weapons, and CraftEngine version fields;
- nodeId;
- matchId and lifecycle revision;
- MatchState;
- configuration summary;
- metric snapshots;
- recent alerts;
- optional `SyntheticLoadResult`;
- optional LoadScenario reference;
- known environment limitations.

Reports must not contain player UUID lists, IP addresses, passwords, keys, complete environment variables, or pass/fail performance conclusions.

## Commands

All commands are under `/warsim`; no `/perf` or `/performance` command is registered.

- `/warsim perf status`
- `/warsim perf metrics [component]`
- `/warsim perf alerts`
- `/warsim perf snapshot`
- `/warsim perf export`
- `/warsim perf scenarios`
- `/warsim perf scenario <id>`
- `/warsim perf dryrun <id>`
- `/warsim perf synthetic <id> [iterations]`
- `/warsim perf synthetic status`
- `/warsim perf synthetic cancel`
- `/warsim perf reset`

## Current limitations

This implementation does not claim any TPS, MSPT, throughput, or 100-player capacity result. Those measurements must be run later by the user on the target server environment.
