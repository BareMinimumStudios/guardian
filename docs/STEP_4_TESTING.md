# Step 4: container click audit

This alpha adds a first shared Fabric/NeoForge vertical slice: immutable item capture, action correlation, persistence, and lookup for accepted clicks in block-backed menus. It remains a testing checkpoint until player-driven acceptance is complete.

## What is recorded

The server hook brackets the actual `AbstractContainerMenu.clicked` invocation after vanilla packet scheduling and menu validation. It reads authoritative slots, the complete player inventory (including offhand), and cursor contents. Client-provided changed-slot maps are never used as audit data. Unchanged actions produce no transaction.

Logical container addresses retain dimension, block position, and slot. A double chest is resolved into its two backing block entities. A transaction retains its container context even when only the cursor changes, such as a creative clone. Player inventory and cursor changes use the player's UUID.

Each nonempty item is encoded at count one through Minecraft's registry-aware `SINGLE_ITEM_CODEC`. The actual count is stored separately. Persistent Data Components and explicit removals are preserved. Compound keys are ordered before encoding so equivalent custom data does not create a false change. Encodings have a format/version header and bounded compressed/decompressed sizes. A transient component patch without a persistence codec causes an explicit capture failure.

One immutable transaction enters the bounded writer queue as one entry. The SQL write commits its header, complete changed-slot payload, and container locations together. Its UUID deduplicates retries. Schema 2 adds separate transaction tables and leaves block tables intact.

## Commands and settings

- `/guardian transactions <x> <y> <z>` returns up to ten recent transactions in the command source's dimension. Relative coordinates work. It uses `guardian.lookup` (operator level 2 by default).
- `/guardian status` includes container submissions, capture failures, and backpressure counters.
- `logging.containerTransactions` is a live switch. The general and logging master switches also apply.

Back up databases before upgrading. Schema 1 is migrated automatically; schema 2 requires this checkpoint or a newer compatible build. Flushes are asynchronous, so allow a flush interval before expecting newly queued transactions in lookup results.

## Player acceptance on each loader

1. Open a chest, barrel, hopper, shulker box, and furnace. Confirm basic take/put clicks and stack splits record their changed container, player, and cursor slots.
2. Repeat with a double chest and query either half. Confirm slot positions refer to the actual half.
3. Shift-click a stack. Confirm one transaction contains both sides, with no duplicate records after a restart.
4. Test hotbar number-key and offhand swaps, pickup-all, drag distribution, and creative clone. Check all actual item changes share the action's transaction ID.
5. Use named, damaged, enchanted, custom-data, and nested-container items. Confirm no capture failures and retained component payloads. A component-only change should be identified in lookup output.
6. Cancel a click with a protection mod, make an unchanged click, and send a stale/closed menu action. Confirm no invented successful transfer appears.
7. Stop normally and restart. Confirm history persists and the writer reports no failures.
8. Disable container logging live, repeat a click, and confirm it creates no transaction. Re-enable it and repeat.

## Boundaries of this checkpoint

Supported menus must expose slots backed by block containers, the player's inventory, or their cursor. Menus with unknown backing inventories are skipped. Ender chests, crafting/trading menus, and entity inventories are outside this first slice. Close-time cursor returns, standalone player-inventory/drop packets, and automated transfers are not captured yet. Logging a THROW click describes the item leaving the inventory; it does not add entity tracking.

Container rollback is not enabled. Fluids and entity logging remain outside Step 4. Dedicated idle-server smoke tests establish startup and migration behavior, not player click acceptance.
