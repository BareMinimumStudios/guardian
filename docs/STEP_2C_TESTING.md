# Step 2C manual server test

Use Java 21 and a clean Fabric 1.21.1 test server. Keep a backup: Step 2C includes the first code that intentionally mutates the world from stored history.

## Build

```bash
./gradlew clean test
./gradlew clean build
```

Install Guardian with Fabric API, Fabric Language Kotlin, and Fzzy Config. LuckPerms is optional.

## 1. Lookup

Place and break a few blocks, then try:

```text
/co lookup t:10m r:10
/co l u:<yourname> t:10m r:10 a:place
/co l t:10m x:0 y:64 z:0 l:20
```

Verify results are newest first and show the expected actor, action, block, and coordinates.

## 2. Inspector

```text
/co inspect
```

Attack and right-click a previously changed block. Normal interaction should be cancelled while inspector is enabled and its history should appear in chat. Disable with:

```text
/co inspect off
```

## 3. Basic rollback

Create a small test area, place several blocks, then run a tightly bounded rollback:

```text
/co rollback t:5m u:<yourname> r:5 a:place
```

Expected:

- selected placed blocks return to their recorded before state;
- no unloaded chunks are force-loaded;
- completion reports applied/skipped/failed counts;
- rolled-back rows remain visible to ordinary lookup with a `[rolled back]` marker;
- another rollback excludes finalized rows.

## 4. Conflict safety

1. Create a logged block change.
2. Change the same location by a route Step 2B does not capture, or stop Guardian capture and modify it.
3. Run a rollback selecting the older logged change.

Expected: Guardian reports the row as skipped due to newer state and does not overwrite the live block. Older selected rows at that same position should be blocked for that rollback chain.

## 5. Block entity restoration

Test a block entity already supported by Step 2B capture, such as a chest or sign. Ensure the history row contains an EXNBT payload, then roll it back in a controlled test area. Verify block state and stored block-entity data are reproduced exactly. If exact verification fails, Guardian should report a failure rather than finalize the row.

## 6. Unloaded chunk behavior

Create history in a distant chunk, unload it, then issue a rollback whose radius/time filter includes it without visiting the chunk. Guardian should skip the row as unloaded rather than force-loading it.

## 7. Journal inspection

After stopping the server cleanly, inspect SQLite:

```sql
SELECT rowid, time, x, y, z, action, rolled_back
FROM ex_block
ORDER BY rowid DESC
LIMIT 50;
```

Expected rollback journal values:

- `0` ACTIVE
- `1` ROLLED_BACK
- `2` PENDING (normally transient; may remain after an interrupted/failing finalization)

A PENDING row is intentionally still eligible for a later rollback attempt so live state can be reconciled.

## Current scope caveats

Step 2C only rolls back block rows captured by the current Step 2B path. Do not use this checkpoint as a production replacement for mature CoreProtect behavior yet. Multi-block placement side effects, containers, environmental changes, entities, and WorldEdit operations are not complete.
