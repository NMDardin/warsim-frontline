# Objective Sectors

T-015 extends the existing objective system with ordered sectors. A sector is a
named phase that owns one or more configured objective IDs. When sectors are
enabled, only objectives in the current active sector are scanned, displayed,
and allowed to progress.

## Configuration

`objectives.sectors.enabled=false` keeps the old single-layer objective
behavior. When sectors are enabled, each configured objective must appear in
exactly one sector, `initial-sector` must exist, sector `order` values must be
unique, and `completion-mode` currently supports only
`ALL_OBJECTIVES_CAPTURED`.

```yaml
objectives:
  enabled: true
  scan-interval-ticks: 5
  sectors:
    enabled: true
    initial-sector: "sector_1"
    completion-mode: "ALL_OBJECTIVES_CAPTURED"
    advance-delay-seconds: 5
    attacker-victory-on-final-sector: true
    definitions:
      sector_1:
        display-name: "第一防线"
        order: 1
        objective-ids: [ "alpha", "bravo" ]
  points:
    alpha:
      display-name: "A点"
      sector: "sector_1"
```

The optional `points.<id>.sector` field is a validation hint. If present, it
must match the sector definition that lists the objective.

## Runtime

On each Match, the initial sector starts as `ACTIVE` and the rest start as
`LOCKED`. A sector completes when every objective in it is owned by attackers.
Non-final sectors advance after `advance-delay-seconds` using the existing
central Match tick; no extra Bukkit task is created. The final sector ends the
Match through `MatchEndReason.OBJECTIVE_COMPLETED` when
`attacker-victory-on-final-sector=true`.

Completed sectors do not roll back in this version. Active-sector objectives
still use the existing capture, neutralize, contest, and reward rules. Ticket
depletion remains an independent Match end path.

## Limits

This version does not implement spawn relocation, sector rollback, sector-based
ticket rewards, world scanning, block changes, weapons, vehicles, or persistent
cross-server sector state. T-013 reset and T-014 destruction restore continue to
own round cleanup.
