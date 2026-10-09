# Container persistence acceptance

Date: 2026-10-09. Runtime remains `0.4.0-alpha.53+1.21.1`; this pass adds validation evidence without a runtime change.

Two fresh isolated server processes passed 154 checks, 77 each on Fabric and NeoForge. Reflection-only fixture agents called Guardian's existing inventory adapter. They did not transform Minecraft bytecode, create rollback journals or enable item apply commands. Original dedicated servers and their databases were untouched.

| Inventory | Complete slots | Saved coal count |
| --- | ---: | ---: |
| Barrel | 27 | 4 |
| Chest | 27 | 5 |
| Hopper | 5 | 6 |
| Dispenser | 9 | 7 |
| Dropper | 9 | 8 |
| Furnace | 3 | 9 |
| Blast furnace | 3 | 10 |
| Smoker | 3 | 11 |

Each fixture began with three coal on disk. An active fixture lease changed the count through the reserved setter, then queued the real chunk save and saved-image read. Closing the binding immediately failed the result observer. A physical prefix fence waited for the actual queued work; a fresh binding read region data without queuing another save and verified the changed count and every unchanged or empty slot. This distinguishes newly persisted contents from a matching older disk image. The synthetic active fixture lease is not a protected production rollback admission or proof of general exclusion.

Shulker boxes, deferred-loot barrels, blocks without inventories and unloaded chunks were refused. Every rejected binding released its fixture lease without leaving an owner reservation. Deferred loot remained intact. Both servers stopped cleanly, SQLite integrity checks passed and no rollback journals were created.

The clean build restored successful test results from Gradle's build cache: 522 tests, no failures. The repeated build reused configuration cache, and the separate WorldEdit build passed. Runtime artifacts remain SQLite-only. [Machine-readable evidence](validation/container-acceptance-alpha53.json) records each loader and tested artifact hash.

## Remaining acceptance

- Connected clients: menus, cursor items, inventory updates, disconnect, death and dimension transfer. Synthetic players or direct adapter calls cannot certify client synchronization.
- Started physical save interruption: interrupt inside a chunk/player write and verify shutdown, durable protection and restart behavior. Closing a result observer after queueing work does not prove this case.
- Compatibility and exclusion: verify supported environments and refuse unsupported inventory implementations. The exact vanilla class whitelist does not certify arbitrary mods or off-thread writes.
- Operator integration: permissions, preflight, confirmation, cancellation and recovery messages. Read-only command observation must not clear claims or expose unsafe apply.
- Release review after those gates pass: documentation, packaging and final regressions.

Item rollback apply remains disabled.
