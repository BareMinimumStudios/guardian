# Recover an interrupted item rollback

Alpha.85 can finish a protected partial item rollback without restoring a whole-world backup.
Online item rollback still runs online. This repair runs separately, with gameplay stopped,
using the existing journal's complete original and expected inventories. It finishes the
recorded rollback; it does not undo it, recover arbitrary lost items or provide item redo.

## Preview, confirm, restart

1. Stop the server normally if it is still running. Keep the matching world, player files,
   Guardian configuration and `guardian.sqlite` together. Use the same server installation,
   world selection, mods and Minecraft 1.21.1 version. Keep a stopped-server backup before
   repair. Guardian's normal startup refusal reports the unresolved operation UUID; the
   online `/guardian rollback-items recovery` diagnostics also list retained operations.
2. Add this **JVM argument**, replacing the placeholder with that exact UUID:

   ```text
   -Dguardian.itemRecovery=preview:<operation UUID>
   ```

   Put it before `-jar` in a Fabric launch command, or on its own line in NeoForge's
   `user_jvm_args.txt`. A server host may label these Java arguments or startup flags.
   This is not a Minecraft chat command or a server program argument.
3. Run the server once. Guardian takes the real exclusive world and database locks, stages
   recovery, prints the selected world, affected files and an exact confirmation flag, then
   exits. World/player file contents are unchanged by preview. Existing interrupted journal
   bookkeeping may advance `APPLYING` to `RECOVERY_REQUIRED` when SQLite opens.
4. Check the world and operation, then replace the preview argument with the **entire flag
   printed by that preview**, in this form:

   ```text
   -Dguardian.itemRecovery=apply:<operation UUID>:<token>
   ```

   Run once and wait for `GUARDIAN_ITEM_RECOVERY_COMPLETE`. All staged target files and the
   durable completion receipt must verify before protection is released. Neither maintenance
   run starts gameplay or accepts player connections. Success exits with code 0; refusal
   exits with code 2. These markers appear in the server log/console.
5. Remove the maintenance argument, then start normally. If more protected operations exist,
   startup reports the next one; preview and confirm each separately.

For a protected operation still in `PREPARED`, no item setter was admitted. Preview instead
prints `GUARDIAN_ITEM_RECOVERY_UNSTARTED` and a cancellation confirmation. Apply prints
`GUARDIAN_ITEM_RECOVERY_CANCELLED_UNSTARTED`, releases only that operation's claims and makes
no inventory-file changes. It works even when an initial player save never created a file.

## If repair is interrupted

Keep the database, world and `guardian/item-recovery/` directory together. Leave gameplay
stopped and repeat the same apply flag and token. Recovery accepts a mixture of exact staged
before/after files, skips files already repaired and finishes the remaining replacements.
Protection remains until every target and the receipt verify. If the last replacement
succeeded but journal completion did not, the same retry can complete that receipt.

An already completed or cancelled operation reports `GUARDIAN_ITEM_RECOVERY_ALREADY_RESOLVED`
without rewriting files. An old token cannot reapply repaired contents over later gameplay.
Never delete journal rows or recovery copies to bypass a refusal.

## What is checked and retained

- Every slot of an affected inventory must match its recorded original or expected immutable
  snapshot. Counts and saved Data Components must match exactly, including unchanged slots.
  A mixed partial image can be finished; an unknown third state is refused.
- Player UUID, saved data version, chunk coordinates/full status, physical block state and
  native container type are verified. Unrelated player/chunk NBT is retained.
- Preview stores complete before/after copies of only the affected player `.dat` and region
  `.mca` files under `guardian/item-recovery/<operation UUID>/`, with a checksummed `plan.bin`.
  The plan binds the journal images, canonical world path, file hashes and confirmation token.
  These copies can contain unrelated player/chunk data; protect them like server backups.
- Confirmation checks every target's complete file hash before replacement, including
  unrelated contents. Missing, corrupt or third-state files/copies stop repair. Each target
  is installed through an atomic replacement on its own filesystem, forced to storage and
  read back. No in-place overwrite fallback is used.

## Limits

SQLite only; supported exact vanilla block inventories and recorded 41-slot player images.
The online limits remain 50 source transactions, 8 inventories, 64 final changed slots and
2 MiB of full inventory snapshot data. New online operations save their original images
before admitting item writes and validate the expected native recovery representation.
Expected affected chunks must be at most 512 KiB uncompressed; external `.mcc` chunk streams
are refused before item writes. Custom storage, loot-backed containers, unsupported saved
item fields and mismatched data versions are refused.

A recovery file is limited to 128 MiB, with at most 512 MiB of committed before/after copies
per operation. Leave additional disk space for staging and a replacement temporary file.
Copies are retained for review; there is no automatic cleanup. An interrupted preparation
without a committed manifest can be previewed again, subject to the bounded directory limit.

Older interrupted operations may lack a saved original baseline or contain unsupported data.
Recovery refuses those conflicts rather than guessing. Preserve the refusal log and copies
for individual review; a consistent backup remains the fallback when images cannot be proven.
Do not run editors or another server against these files during maintenance. Uncooperative
writers, arbitrary mod storage, hardware failure and remote-filesystem durability are not
certified. Both-loader tests cover real process interruption and restart, not power loss.

See [validation evidence](validation/item-recovery-alpha85.json) and the
[online rollback contract](ONLINE_ITEM_ROLLBACK.md). Connected-client and production-pack
acceptance remain pending.
