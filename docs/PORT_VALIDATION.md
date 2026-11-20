# Experimental rollback validation

Alpha.98 contains a fix for block-entity-only rollback and redo. Vanilla can return false
when asked to set an unchanged block state; Guardian previously treated that as a failure
before loading the recorded block-entity data. The restorer now keeps the existing entity
when the state already matches, validates payloads before mutation and verifies the state.
The test reproduced the failure before the fix and passed afterward.

## Alpha.99 creative deletion recovery

All listed versions plus the 1.21.1 baseline passed eight creative-recovery stages on
both loaders. Actual accepted creative-delete packets produce source history; the public
preview/apply/confirm commands recover named and damaged items and refuse repeated source
recovery. A process interruption after the first saved recovery write is repaired through
maintenance preview/confirmation, with protected startup, wrong-token refusal, idempotent
retry and complete player-file readback. Preview/refusal/retry leave native inventory
files unchanged. [Evidence and limits](validation/creative-recovery-alpha99.json).

These are isolated synthetic native-player tests, not connected-client or modpack
acceptance. The alpha.98 transfer and block scenarios below remain prior regression
coverage, separate from creative recreation authority.

## Tested targets

Fabric and NeoForge each passed the scenarios below on Minecraft **1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1.2 and 26.2**.
The retained **1.21.1** baseline also passed the fixed native block/redo and restart checks.
All worlds, databases, identities and fixtures were isolated from supplied server data.

## Item rollback and recovery

Each newer loader/version pair passed this sequence:

1. Confirm rollback through the production online service for chests, barrels, hoppers,
   dispensers, droppers, furnaces, blast furnaces and smokers, plus a native online player.
   Selection changes nothing; a wrong token is refused. Counts, named Data Components,
   complete saved inventories and the durable acknowledged receipt are checked.
2. Refuse player eligibility with an occupied cursor, then accept the idle inventory.
   The fixture supplies a real server-player/listener object without a connected client.
3. Interrupt after the first actual slot write in a player-to-container rollback. Save and
   decode the partial player file, verify it contains the changed slot, then halt the JVM
   without normal shutdown. Durable protection remains.
4. Attempt normal startup and verify protected refusal before gameplay proceeds.
5. Run maintenance preview with the exact recorded Minecraft version. Hashes of native
   inventory files remain unchanged. An incorrect apply token also leaves them unchanged.
6. Apply targeted recovery, verify SQLite integrity, completed phase and acknowledged
   receipt. Repeating apply reports already resolved and changes no inventory files.
7. Start normally and compare every recovered player slot/component against the expected
   journal image; the container counterpart is empty, preserving item conservation.

The normal native fixture has 76 assertions per pair. The interrupted-write fixture has
11; restart verification has four. Maintenance checks additionally verify log outcomes,
file hashes and database state. These counts are separate from the earlier build and
inventory-probe totals in the support matrix.

An additional 26.2 Fabric fixture exercised a physically saved partial block-to-block
transfer. A stale staged plan was also refused after an intervening normal startup
resaved its region file; that refusal preserved protection instead of overwriting it.

## Block rollback and redo

Each newer pair, plus both baseline loaders, passed 20 native assertions and four restart
assertions. They cover read-only rollback/restore previews, an ordinary block change,
exact block-entity payload restoration with an unchanged block state, redo, refusal to
overwrite a newer conflicting block, and the saved redo state after a normal restart.
History is seeded with immutable snapshots; these scenarios test restoration, not player
placement capture or every modded block's hooks.

## Limits

These tests do not mark the ports release-ready. Connected-client menu behavior, actual
permission-provider and modpack acceptance remain separate. The item fixture seeds a
valid recorded transfer, so it does not certify every capture path. This pass interrupted
online rollback after a saved partial write; it did not interrupt the maintenance installer
between each file replacement on every new target. It does not simulate power loss,
hardware failure or remote-filesystem durability. Existing automated tests cover additional
recovery conflicts and engine retry paths; those are not native gameplay certification.

Keep the exact Minecraft version, world, database and recovery directory together.
Do not start normally between maintenance preview and apply. Incompatible data versions,
unknown inventory states and changed staged files remain refusals, with no force option.
