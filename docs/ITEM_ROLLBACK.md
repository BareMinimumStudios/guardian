# Item rollback preview

Checkpoint: `0.4.0-alpha.10+1.21.1`.

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

Changed slots, missing or unloaded containers, sealed loot, busy players and endpoints outside the selection are skipped. Sealed loot remains sealed and chunks are not loaded to inspect them. Shared-inventory records with identical timestamps are skipped because the stored history cannot establish their order.

Crafting, creative changes, drops, close-time returns, temporary cursor/grid addresses and item transformations are excluded. The preview does not turn a recipe into its ingredients or recreate a dropped item.

The preview checks at most 50 transactions, or the configured rollback record limit if lower, 32 logical inventories and 2,048 changed slots. Larger selections are refused instead of silently truncated. It reads one inventory per server tick, and only one preview can run at a time.

## What remains before apply

Observations collected over several ticks can become stale. A successful preview is not a reservation or a promise that a later apply will succeed. It also cannot prove that a container was never replaced or that unrelated newer history is absent.

Apply needs a persistent transaction journal, fresh checks against newer history, inventory ownership and container identity, interruption handling and recovery across world/player saves. Those checks will precede any slot writes. Player-driven cross-inventory acceptance and crash recovery are still pending; preview-only results do not establish those guarantees.
