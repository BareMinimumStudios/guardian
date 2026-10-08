# Item rollback preview

Checkpoint: `0.4.0-alpha.12+1.21.1`.

This milestone checks which recorded item transfers could be reversed. It does not change items. There is no item rollback apply command yet.

## Try a preview

```text
/guardian rollback-items preview t:10m r:5
/guardian rollback-items preview t:1h u:Cherry r:5 x:100 y:64 z:100
```

Use `guardian.rollback` permission, or vanilla operator level 2. Time is required. `user:` and `time:` are aliases for `u:` and `t:`. Coordinates must be supplied together; otherwise the center is your current position. The current dimension is used. Fabric WorldEdit adapter users can use `r:#worldedit` for an existing cuboid selection. The default radius and maximum radius come from rollback settings. Result paging and ordering parameters are not accepted for a preview.

Both physical block endpoints must be inside the selected region. A player endpoint must be online with the ordinary inventory screen, an empty cursor and an empty crafting grid. Offline inventories are not read from player files.

## Reading the result

Eligible means that all observed changed slots match their recorded after-state, and reversing the transaction conserves item counts and components. The planner simulates transactions newest first on a private copy. A skipped transaction blocks older dependent history; independent inventories can still qualify.

Changed slots, missing or unloaded containers, sealed loot, busy players and endpoints outside the selection are skipped. The preview also checks persisted history outside your filters. If an excluded transaction touches a participating inventory at or after the earliest selected change for that owner, the candidate is skipped. An equally timed excluded record is also unsafe. This check includes player cursor and crafting actions through their player owner, and applies to the whole inventory rather than just matching slot numbers.

Recorded block history at or after the container's selected item history invalidates that position, even if the block change was rolled back. Unfinished journal reservations and previously claimed transactions are skipped as well. Sealed loot remains sealed and chunks are not loaded to inspect them. Shared-inventory records with identical timestamps are skipped because the stored history cannot establish their order.

Crafting, creative changes, drops, close-time returns, temporary cursor/grid addresses and item transformations are excluded. The preview does not turn a recipe into its ingredients or recreate a dropped item.

The preview checks at most 50 transactions, or the configured rollback record limit if lower, 32 logical inventories and 2,048 changed slots. Larger selections are refused instead of silently truncated. It reads one inventory per server tick, and only one preview can run at a time.

## What remains before apply

Observations collected over several ticks can become stale. A successful preview is not a reservation or a promise that a later apply will succeed. It checks persisted newer history and recorded block changes, but cannot prove that an unlogged replacement never occurred or that pending audit writes are already persisted.

The persistent journal now records a bounded transfer chain atomically with inventory reservations and source transaction claims. Preparation verifies the source payloads against stored history and checks owner history, recorded block changes and existing claims inside the same database transaction. A second operation cannot reserve the same logical inventory or claim an already completed source. These reservations coordinate journal operations; they do not freeze ordinary gameplay inventories.

Journal phases are PREPARED, APPLYING, RECOVERY_REQUIRED, COMPLETED and CANCELLED. Only a PREPARED operation can be cancelled and release its source claims. Terminal phases release inventory reservations; completed source claims remain to prevent a second reversal. Transitions use an expected phase so stale callbacks cannot advance a changed operation. The future coordinator must establish saved-world durability before calling completion; the storage method itself is not proof that Minecraft saved items.

On startup, an APPLYING journal becomes RECOVERY_REQUIRED and retains its claims. It is not replayed. Recovery observations compare exact counts/components with original and restored slots. Partial, conflicting, missing and cyclic indistinguishable states remain unresolved. `/guardian status` reports `itemRecovery`, and startup warns if unfinished operations exist. Ordinary previews do not create journals. Recovery listing reads headers only and loads one bounded payload on request.

Apply still needs an audit-write barrier and fresh checks at the moment of mutation, live inventory ownership and container identity, plus recovery coordinated with durable world/player saves. The journal alone cannot make a multi-inventory Minecraft write atomic. Those checks will precede any slot writes. Player-driven cross-inventory acceptance and crash recovery are still pending; preview-only results do not establish those guarantees.

## Owner index migration

Schema 8 records each transaction's changed logical owners and timestamp. The index is part of the same atomic audit write as the source payload and locations; a retried UUID cannot add different owners. Existing records are decoded in pages of 128 during the one-time migration, retaining only owner keys between rows. This startup work scales with existing item history. Source payloads are unchanged. A corrupt payload aborts the migration instead of leaving a partial index or certifying incomplete history.

Back up the stopped database before upgrading. Config version 6 and GCT1/GCT2 payload formats stay the same. SQLite is the standard backend; optional DuckDB follows the same migration and checks. The nonpersistent memory backend cannot establish these guarantees and refuses item rollback preview.
