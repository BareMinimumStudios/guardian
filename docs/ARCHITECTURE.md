# Guardian architecture — Step 3

## Shared core and loader boundaries

The `common` module has no Minecraft, Fabric, NeoForge, or WorldEdit imports. It owns the immutable domain values, JDBC persistence, queueing, command filter parser, and pure rollback reconciliation. Fabric packages its compiled output directly into the core mod jar.

Minecraft snapshots, restoration, event installation, commands, permissions, configuration, and the integration API remain in the Fabric module at this checkpoint. Those Minecraft boundaries still need a NeoForge implementation. The WorldEdit adapter is a separate GPL artifact.

## Integration boundary

The BML core does not import WorldEdit. Optional integrations use a narrow API owned by Guardian:

```text
integration/api/
  GuardianIntegrationApi
  RegionSelectionProvider
  RegionSelection
  RegionSelectionResult
```

The WorldEdit module translates WorldEdit-specific objects into these Guardian types and into the same immutable `BlockChangeSnapshot` used by ordinary Fabric capture.

## WorldEdit edit-session capture

WorldEdit builds an extent chain for each `EditSession`. The adapter subscribes to `EditSessionEvent` only at `BEFORE_CHANGE`, the stage intended for loggers immediately before native world mutation.

```text
WorldEdit operation
   -> EditSessionEvent(BEFORE_CHANGE)
   -> reserve BulkCaptureSession
   -> GuardianWorldEditExtent
       -> snapshot exact native BEFORE state
       -> delegate WorldEdit mutation
       -> snapshot exact native AFTER state
       -> classify place/break/change
       -> record ChangeCause.WORLD_EDIT
```

If no bulk reservation is available, a rejecting extent prevents the edit from starting. If a running edit reaches its configured operation ceiling, the next block is rejected before mutation.

If post-mutation snapshotting fails, Guardian attempts to restore the exact before snapshot before throwing. Any audit records already captured by that operation are flushed before a Guardian-triggered abort propagates.

## Streaming bulk lane

The ordinary `BufferedLogPipeline` intentionally uses a bounded, nonblocking queue so a player action never stalls the server thread waiting on JDBC. A WorldEdit command can generate tens or hundreds of thousands of changes and needs different ingress behavior.

`BulkAuditDispatcher` therefore provides an operation-aware lane:

1. a semaphore reservation is acquired before the edit mutates the world;
2. `BulkCaptureSession` accepts at most the configured total entries;
3. entries stream in 1,024-entry chunks by default instead of accumulating the entire edit until close;
4. `Guardian-BulkAudit` submits those entries to the normal writer and retries `BACKPRESSURE` asynchronously;
5. a FIFO operation-boundary marker releases the reservation only after all earlier chunks have been submitted to the writer queue.

The configured `maxPendingOperations` caps the number of active/draining external edits. `maxEntriesPerOperation` is a hard audit safety ceiling, not an estimate.

### Durability boundary

The bulk lane is memory-backed. Records become covered by Step 2A's persistent database guarantees only after they have progressed into the writer/storage path. An abrupt JVM/OS/process loss can lose the unpersisted ingress tail. A disk-backed write-ahead ingress journal is deliberately left as future hardening rather than falsely claiming stronger durability.

## Selection scope

`r:#worldedit` is resolved by the optional registered `RegionSelectionProvider`. Step 3 accepts only WorldEdit `CuboidRegion` selections and converts them to platform-neutral inclusive `BlockBounds`.

Both in-memory and JDBC query paths then use exact X/Y/Z range predicates. The storage layer has no WorldEdit dependency.

Non-cuboid WorldEdit selections are rejected rather than approximated with a cuboid bounding box, because such approximation could query or roll back blocks the user never selected.

## Rollback interaction

WorldEdit-created rows are ordinary Guardian block-history rows with `ChangeCause.WORLD_EDIT`. The Step 2C rollback safety model is unchanged:

- no forced chunk loads;
- newest-to-oldest history;
- live state must match recorded `after` (or already-restored `before` for journal recovery);
- unsafe rows block older rows at that position;
- exact block-state/block-entity restoration and verification;
- ACTIVE/PENDING/ROLLED_BACK journal.

`r:#worldedit` changes only the spatial query scope, not these safety rules.

## Status and diagnostics

`/co status` reports:

- database backend/schema;
- writer state, queued/accepted/persisted counts, backpressure and failures;
- bulk reserved operations, queued operation boundaries, queued entries, streamed chunks, submitted entries, backpressure retries, rejected reservations and failure state.

These counters are intended to make pressure tests observable rather than relying on subjective server behavior.
