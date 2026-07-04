# Weapons Catalog

T-016 keeps Weapons as an independent Paper plugin:

- Main Paper plugin: `WarSimFrontline`
- Weapons Paper plugin: `WarSimFrontlineWeapons`
- Velocity plugin remains separate
- The main plugin owns `/warsim`; Weapons only registers the `weapon` command extension
- Weapons obtains shared runtime services through Bukkit `ServicesManager`
- The main plugin does not statically depend on `warsim-weapons-paper`

## Formal Infantry IDs

The bundled Weapons catalog now includes the first formal infantry set:

| ID | Category | Role | Magazine / reserve | Reload | RPM | Range | Spread | Head | Damage |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `wrench_m1895_rifle` | `RIFLE` | standard rifle | 5 / 40 | 2600 ms | 55 | 180 | 0.30 | 1.8 | 38 / 34 / 22 |
| `wrench_m1911_pistol` | `PISTOL` | sidearm | 7 / 35 | 1700 ms | 240 | 80 | 0.85 | 1.5 | 24 / 20 / 12 |
| `wrench_m1918_smg` | `SMG` | close range | 20 / 100 | 2200 ms | 450 | 70 | 1.45 | 1.35 | 19 / 15 / 9 |
| `wrench_m1915_lmg` | `LMG` | support placeholder | 30 / 120 | 3500 ms | 350 | 120 | 1.00 | 1.45 | 26 / 22 / 14 |
| `wrench_m1897_shotgun` | `SHOTGUN` | close range placeholder | 5 / 30 | 2800 ms | 70 | 45 | 2.50 | 1.2 | 52 / 32 / 12 |
| `wrench_m1903_marksman` | `MARKSMAN` | long range | 5 / 35 | 2800 ms | 45 | 200 | 0.18 | 2.0 | 45 / 42 / 30 |

The shotgun is still a single hitscan weapon in this phase. It does not use
pellet spread. The LMG uses the existing semi-auto weapon engine; this phase
does not add automatic-fire mechanics.

## Class Loadouts

Official class defaults reference formal IDs:

- Assault: `wrench_m1895_rifle`, `wrench_m1911_pistol`
- Medic: `wrench_m1895_rifle`, `wrench_m1911_pistol`
- Support: `wrench_m1918_smg`, `wrench_m1911_pistol`
- Scout: `wrench_m1903_marksman`, `wrench_m1911_pistol`

`test_rifle`, `test_pistol`, and `test_smg` remain in the bundled catalog only
for development compatibility. Official class loadouts must not reference them.

## CraftEngine Placeholders

The development CraftEngine template defines `warsim:<weapon-id>` items for all
six formal IDs, plus placeholder model JSON files under:

```text
dev-environment/craftengine/resources/warsim/resourcepack/assets/warsim/models/item/
```

These are internal placeholders using vanilla textures. They are not final
resource-pack art and were not client-validated in this implementation pass.

## Commands

Weapons commands remain under the main `/warsim` root:

- `/warsim weapon list` shows ID, display name, category, magazine/reserve,
  damage range, CraftEngine item ID, and `enabled=true`
- `/warsim weapon inspect <weaponId>` shows a configuration summary
- `/warsim weapon inspect` without an ID still inspects the player's main hand
- Unknown or invalid weapon IDs return explicit errors

## Out Of Scope

T-016 does not add artillery, vehicles, explosive weapons, automatic weapons,
new ballistics, recoil animation, resource-pack final art, or client acceptance
results.
