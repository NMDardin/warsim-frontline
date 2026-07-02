# WarSim Load Test Map Templates

This directory contains load-test map configuration examples only.

- The default world name is `warsim_load_test`.
- These files do not create, copy, download, or modify a Minecraft world.
- All coordinates are template coordinates and must be confirmed or adjusted by an administrator before use on a real load-test map.
- T-010 `prepare` creates only an in-memory scenario context. It does not teleport players, spawn NPCs, place blocks, start matches, or run synthetic load.
- The blocked weapon lane requires administrators to build the wall in the real test world before future server-side validation.

Copy the example files into the Paper node plugin data folder:

```text
plugins/WarSimFrontline/config/load-maps.yml
plugins/WarSimFrontline/config/load-scenarios.yml
```

Then use:

```text
/warsim loadmap validate warsim_load_test
/warsim loadmap scenarios
/warsim loadmap scenario mixed_100
/warsim loadmap prepare mixed_100
/warsim loadmap clean
```

These commands are diagnostics and preparation only. They do not run performance tests.
