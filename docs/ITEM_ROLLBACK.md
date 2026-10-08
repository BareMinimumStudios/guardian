# Item rollback preview

Checkpoint: `0.4.0-alpha.15+1.21.1`.

This milestone waits for accepted audit writes and checks which recorded item transfers could be reversed. It does not change items. There is no item rollback apply command yet.

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

The preview checks at most 50 transactions, or the configured rollback record limit if lower, 32 logical inventories and 2,048 changed slots. Larger selections are refused instead of silently truncated. It reads participating slots from one inventory per server tick. Preview and recovery share one active check. Retained item payloads are capped at 16 MiB; an excessive observation is unavailable.

## Inspect unfinished journals

```text
/guardian rollback-items recovery
/guardian rollback-items recovery <operation UUID>
```

The same `guardian.rollback` permission or operator level 2 is required. Listing shows at most ten unfinished headers; click one to suggest its command. Their UUIDs are available for completion after listing. A specific check loads one bounded journal, observes participating slots one owner per tick, waits for the accepted audit prefix again and confirms the journal did not change. It times out after ten seconds. Unloaded chunks stay unloaded and sealed loot stays sealed.

| Observation | Meaning |
| --- | --- |
| ORIGINAL | Observed slots match their recorded state before rollback. |
| RESTORED | Observed slots match the planned restored state. |
| BOTH | Both states are identical; whether apply occurred is unresolved. |
| PARTIAL | Slots contain a mixture of original and restored states. |
| CONFLICT | Items or components match neither expected state. |
| UNAVAILABLE | An owner could not be read or its live identity changed. |

These commands never change items, advance phases or clear claims. ORIGINAL and RESTORED describe sampled live slots; neither establishes durable chunk/player saves. Owners are sampled across ticks, so the result is not an atomic inventory snapshot. Gameplay continues during the check. Treat partial, conflicting, unavailable and indistinguishable states as unresolved; no automatic replay follows a result.

## Captured activity during observation

Preview starts a watch after its initial persisted-history check; recovery starts one after loading and validating its journal. Each watch contains at most 32 persistent owners. A pipeline supports at most 32 active watches. The normal command services still allow only one item check at a time.

Every submitted item or block snapshot invalidates matching owners before the queue accepts or rejects it. A change to any slot affects the whole logical inventory. Cursor and crafting-grid changes affect their player's inventory owner. Repeated changes, including moving items away and back, never reset a watch. Other owners and dimensions remain independent.

The final result marks affected owners unavailable. Queue rejection does not hide captured activity from these watches. Completion, refusal, timeout and shutdown release the watch; writer failures invalidate outstanding handles. Registration, submission and inspection share a short ordering gate, with no storage calls or completion callbacks under that gate. Watches retain bounded owner identities, not item data or historical transactions.

This only covers snapshots submitted during the watched interval. Disabled logging, unsupported capture paths, failures before submission and other mod writes remain outside the guarantee. The watch does not lock an inventory, prevent a transfer or establish a disk save.

## What remains before apply

Observations collected over several ticks can become stale. A successful preview is not a reservation or a promise that a later apply will succeed. After reading owners, it repeats the accepted-prefix barrier and persisted-history check. It then checks captured live object identities, block state, container size, sealed loot and player menu identity. These checks detect observed owner changes but do not freeze contents, prove that no unlogged mutation occurred or cover changes accepted after the final barrier.

The persistent journal now records a bounded transfer chain atomically with inventory reservations and source transaction claims. Preparation verifies the source payloads against stored history and checks owner history, recorded block changes and existing claims inside the same database transaction. A second operation cannot reserve the same logical inventory or claim an already completed source. These reservations coordinate journal operations; they do not freeze ordinary gameplay inventories.

Journal phases are PREPARED, APPLYING, RECOVERY_REQUIRED, COMPLETED and CANCELLED. Only a PREPARED operation can be cancelled and release its source claims. Terminal phases release inventory reservations; completed source claims remain to prevent a second reversal. Transitions use an expected phase so stale callbacks cannot advance a changed operation. The future coordinator must establish saved-world durability before calling completion; the storage method itself is not proof that Minecraft saved items.

On startup, an APPLYING journal becomes RECOVERY_REQUIRED and retains its claims. It is not replayed. Recovery observations compare exact counts/components with original and restored slots. Partial, conflicting, missing and cyclic indistinguishable states remain unresolved. `/guardian status` reports `itemRecovery`, and startup warns if unfinished operations exist. Ordinary previews do not create journals. Recovery listing reads headers only and loads one bounded payload on request.

The audit-write barrier is available, but apply still needs gameplay coordination and a fresh barrier/history check at the moment of mutation, exclusive live inventory coordination at mutation time, plus recovery coordinated with durable world/player saves. The journal alone cannot make a multi-inventory Minecraft write atomic. Those checks will precede any slot writes. Player-driven cross-inventory acceptance and crash recovery are still pending; preview-only results do not establish those guarantees.

## Owner index migration

Schema 8 records each transaction's changed logical owners and timestamp. The index is part of the same atomic audit write as the source payload and locations; a retried UUID cannot add different owners. Existing records are decoded in pages of 128 during the one-time migration, retaining only owner keys between rows. This startup work scales with existing item history. Source payloads are unchanged. A corrupt payload aborts the migration instead of leaving a partial index or certifying incomplete history.

Back up the stopped database before upgrading. Config version 6 and GCT1/GCT2 payload formats stay the same. SQLite is the standard backend; optional DuckDB follows the same migration and checks. The nonpersistent memory backend cannot establish these guarantees and refuses item rollback preview.

## Accepted audit prefix

Before fetching history, preview requests a receipt for the pipeline's current accepted-entry count. The writer acknowledges that prefix after appending its exact pending batches and successfully flushing storage. Later submissions do not have to finish for an older receipt to complete. The server thread waits through a callback and continues ticking.

There can be at most 32 pending receipts per pipeline. Cancellation removes the waiter; storage flush failures and writer lifecycle failures reject it. Preview has a ten-second limit while waiting for the receipt and history preparation. Shutdown cancels its session and ignores late callbacks. The writer checks pending receipts between batches and polls an idle queue at most every 100 milliseconds.

This receipt covers accepted pipeline entries, not rejected captures, backpressure losses, other work still waiting to submit or future gameplay changes. It is not an inventory reservation or a world/player save receipt. Recovery apply will need those separate checks and a fresh accepted-prefix barrier while participating inventories are coordinated. Callbacks must schedule their work on the appropriate executor; they run outside the pipeline ordering gate.
