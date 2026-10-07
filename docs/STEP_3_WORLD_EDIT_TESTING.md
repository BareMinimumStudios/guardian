# Step 3 WorldEdit testing guide

Use a disposable Fabric 1.21.1 server/world for this checkpoint.

## 1. Build

Java 21:

```bash
./gradlew clean test
./gradlew clean build
./gradlew :worldedit-adapter:build
```

Install the produced Guardian core jar plus the Guardian WorldEdit adapter jar.

Also install the normal core dependencies and **WorldEdit Fabric 7.3.8 for Minecraft 1.21.1**. Do not use the current 7.4.x branch as the 1.21.1 runtime target.

## 2. Boot/status

Start the server and run:

```text
/co status
```

Confirm:

- storage backend/schema are healthy;
- normal writer state is RUNNING;
- bulk `failed=false`;
- no WorldEdit adapter initialization exception appears in the log.

## 3. Small WorldEdit edit

Create a small cuboid selection and run for example:

```text
//set stone
```

Then query the area:

```text
/co lookup t:5m r:#worldedit
```

Expected:

- rows appear for actual changed blocks;
- formatting identifies them as `via WorldEdit`;
- the actor is the WorldEdit player;
- unchanged target blocks are not duplicated as changes.

Run `/co status` again and confirm `submitted`/`chunks` advanced.

## 4. Selection-scoped rollback

With the same cuboid selected:

```text
/co rollback t:5m r:#worldedit
```

Expected behavior is the same conservative Step 2C rollback behavior, only spatially constrained to the exact cuboid selection.

Verify that blocks outside the selected cuboid are untouched.

## 5. Selection safety

Create a non-cuboid WorldEdit selection (polygonal/cylinder/etc.) and attempt:

```text
/co lookup t:5m r:#worldedit
```

Step 3 should reject it with an explanatory error. It must not silently convert the region to a larger bounding cuboid.

Also verify that `r:#worldedit` cannot be combined with explicit `x:`, `y:`, or `z:` coordinates.

## 6. Block entity check

WorldEdit-change a small area containing a block entity such as a sign or chest. Query it and, on a disposable copy, rollback it.

Confirm that Guardian's before/after EXNBT payload is present and restoration verifies correctly. Test modded block entities separately if your modpack contains them.

## 7. Pressure test

Start conservatively. A 50 x 50 x 20 cuboid is 50,000 positions; choose a pattern that causes a large number of actual changes.

Before the edit:

```text
/co status
```

Perform the WorldEdit operation, then run status repeatedly after completion.

Expected:

- WorldEdit capture does not silently disappear when the normal queue fills;
- `chunks` increases while the operation is being streamed;
- `retries` may rise under normal-queue backpressure;
- `submitted` eventually reaches the number of captured changes;
- `reserved` returns to zero after the final chunk drains;
- `failed` remains false.

Use SQL or `/co lookup` to independently verify row counts for the test window/region.

## 8. Fail-closed operation limit

On a disposable world, lower `World Edit Max Buffered Changes` in Fzzy Config to a small value such as 1,000 and restart (the setting is restart-required).

Attempt a WorldEdit operation that would actually change more than 1,000 blocks.

Expected:

- at most the configured captured-change ceiling is allowed through;
- the next mutation is rejected before it occurs;
- history for changes that already occurred is still flushed;
- `/co status` eventually returns `reserved=0`;
- there are no changed-but-unlogged blocks caused by Guardian knowingly allowing work beyond its capacity.

WorldEdit may report the operation as failed/aborted; that is intentional fail-closed behavior.

## 9. Pending-operation reservation

With the default maximum of two pending operations, use multiple actors or a controlled test harness to attempt more concurrent/overlapping WorldEdit edits than the configured reservation count.

The excess operation must be refused before its first changed block rather than beginning unaudited.

## 10. Restart verification

After the bulk lane reports no queued/reserved work, stop the server normally and restart it. Re-run the lookup for the test region/time window and confirm history remains available.

## Known Step 3 durability boundary

The bulk ingress lane is memory-backed until entries reach Guardian's persistent writer. A hard JVM/OS/process crash during a huge edit can lose the unpersisted tail. This checkpoint is designed to prevent **queue-overload drops during normal operation**, not to claim a disk-backed ingress WAL that does not yet exist.
