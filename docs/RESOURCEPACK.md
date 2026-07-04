# Resource Pack Delivery

T-017 adds the base delivery and diagnostics path for WarSim resource-pack
content. It does not build, publish, or validate a final client resource pack.

## Manifest

The bundled manifest lives at:

```text
warsim-resourcepack/src/main/resources/warsim-resourcepack-manifest.yml
```

It records the content version `t017-formal-weapons-placeholder`, namespace
`warsim`, and the six required T-016 formal weapon items:

- `warsim:wrench_m1895_rifle`
- `warsim:wrench_m1911_pistol`
- `warsim:wrench_m1918_smg`
- `warsim:wrench_m1915_lmg`
- `warsim:wrench_m1897_shotgun`
- `warsim:wrench_m1903_marksman`

The old development items `warsim:test_rifle`, `warsim:test_pistol`, and
`warsim:test_smg` are optional compatibility entries.

T-017 intentionally keeps YAML reading in the Paper plugin with Bukkit
`YamlConfiguration`. The manifest objects and validator in `warsim-resourcepack`
are platform-neutral. A later offline pack builder can introduce its own parser
or build-time tooling without changing the runtime contract.

## Configuration

Main Paper config uses:

```yaml
resource-pack:
  enabled: true
  content-version: "t017-formal-weapons-placeholder"
  pack:
    url: ""
    sha1: ""
    required: false
    prompt: "WarSim Frontline uses a resource pack for weapon and UI placeholders."
  validation:
    enabled: true
    fail-closed-on-invalid-manifest: false
    expected-namespace: "warsim"
    require-formal-weapon-items: true
    require-placeholder-models: true
  send:
    on-join: false
    delay-ticks: 40
    resend-on-status-failed: false
```

An empty URL never sends a pack request. An empty SHA-1 is allowed for local
diagnostics; a non-empty SHA-1 must be a 40-character hex digest. Production
release needs a real pack zip, public URL, SHA-1, and client acceptance pass.

## Commands

The main plugin registers `/warsim resourcepack` as a child of the existing
root command:

- `/warsim resourcepack status`
- `/warsim resourcepack inspect`
- `/warsim resourcepack players`

The command reports manifest validation, URL/SHA-1 configuration, required and
optional entry counts, and the most recent player resource-pack statuses. It
does not expose filesystem paths to players.

## Player Status

The Paper coordinator records recent `PlayerResourcePackStatusEvent` values:

- `UNKNOWN`
- `SENT`
- `ACCEPTED`
- `DOWNLOADED`
- `SUCCESSFULLY_LOADED`
- `DECLINED`
- `FAILED_DOWNLOAD`
- `INVALID_URL`
- `FAILED_RELOAD`
- `DISCARDED`
- `OTHER`

Declining the pack does not kick the player in the default configuration. The
resource-pack subsystem does not modify Match state, Inventory, XP, EnderChest,
worlds, blocks, or entities.

## CraftEngine Placeholder Relationship

T-016 added CraftEngine placeholder item definitions and placeholder model JSON
files under `dev-environment/craftengine/resources/warsim`. T-017 records the
expected item/model IDs and validates the bundled manifest. Runtime does not
scan `dev-environment`, generate files, package zips, download URLs, or compute
remote hashes.

## Not Included

This task does not provide final art, final pack format validation, vehicle
models, artillery, explosive weapons, automatic-fire mechanics, or client-side
visual acceptance results.
