# Controlled Destruction

T-014 adds a small, controlled block-change ledger for official battle maps. It
records only blocks that appear in Bukkit destruction events and pass the
configured world, material, region, TileState, Match state, and ledger limit
checks.

It does not scan whole worlds, copy world folders, delete world folders, unload
or reload worlds, rebuild worlds, or store full map snapshots. The ledger is
memory-only; a server crash or restart loses any unrestored block snapshots.

## Configuration

```yaml
destruction:
  enabled: true
  record:
    entity-explosions: true
    block-explosions: true
    player-block-breaks: false
    player-block-places: false
  restore:
    enabled: true
    maximum-blocks-per-match: 50000
    maximum-blocks-per-reset: 50000
  worlds:
    - "world"
  materials:
    allow-list:
      - "DIRT"
      - "GRASS_BLOCK"
      - "STONE"
    deny-list:
      - "BEDROCK"
      - "CHEST"
      - "SPAWNER"
  protected-regions: {}
```

Official battle nodes enable destruction by default. Lobby nodes disable it and
do not register destruction listeners. Production maps should configure
`worlds`, `materials.allow-list`, `materials.deny-list`, and
`protected-regions` before launch.

## Recording

During `PLAYING`, explosion listeners inspect `EntityExplodeEvent` and
`BlockExplodeEvent`. Blocks that fail validation are removed from the event
`blockList`; the whole explosion is not cancelled, and entity damage, explosion
radius, sound, particles, and drops are not changed.

For each allowed block, WarSim records the original `BlockData#getAsString()`
only the first time that block is affected in a Match. A later hit to the same
world/x/y/z does not overwrite the original snapshot.

Blocks with TileState data, including containers, signs, spawners, and similar
block entities, are rejected. T-014 does not save inventories, sign text, NBT,
or other block-entity internals.

## Reset Restore

Block restore runs as a callback inside the existing T-013 `RESETTING`
transaction after player evacuation and before transient entity cleanup. The
reset service revalidates the Match context before and after the callback.
Captured revisions are diagnostic: blocks are recorded during `PLAYING`, while
restore runs under the later `RESETTING` revision, so restore ownership is
validated by `matchId`.

If the ledger has more entries than `maximum-blocks-per-reset`, restore fails
closed before changing any blocks. Individual restore failures are logged with
server stack traces, other snapshots are attempted, and the final reset result
is failure. `DefaultMatchService` remains the only owner of Match state.

Successful restore clears the Match ledger. Failed restore keeps the ledger in
memory for diagnostics.

## Current Limits

T-014 does not implement explosive weapons, shells, vehicle cannons, persistent
ledger storage, TileEntity restoration, or multi-objective destruction rules.
Those require later production hardening.
