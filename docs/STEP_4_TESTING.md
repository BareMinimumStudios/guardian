# Step 4: container and player item audit

This alpha adds a first shared Fabric/NeoForge vertical slice: immutable item capture, action correlation, persistence, and lookup for accepted clicks and close-time cursor returns in block-backed menus, plus player inventory moves, standalone drops, offhand swaps, and accepted creative slot changes. It remains a testing checkpoint until player-driven acceptance is complete.

## What is recorded

The server hook brackets the actual `AbstractContainerMenu.clicked` invocation after vanilla packet scheduling and menu validation. It reads authoritative slots, the complete player inventory (including offhand), and cursor contents. Client-provided changed-slot maps are never used as audit data. Unchanged actions produce no transaction.

Logical container addresses retain dimension, block position, and slot. A double chest is resolved into its two backing block entities. A transaction retains its container context even when only the cursor changes, such as a creative clone. Player inventory and cursor changes use the player's UUID.

Each nonempty item is encoded at count one through Minecraft's registry-aware `SINGLE_ITEM_CODEC`. The actual count is stored separately. Persistent Data Components and explicit removals are preserved. Compound keys are ordered before encoding so equivalent custom data does not create a false change. Encodings have a format/version header and bounded compressed/decompressed sizes. A transient component patch without a persistence codec causes an explicit capture failure.

One immutable transaction enters the bounded writer queue as one entry. The SQL write commits its header, complete changed-slot payload, and container locations together. Its UUID deduplicates retries. Schema 2 added separate transaction tables. Schemas 3 and 4 guard new action names against older readers; they do not change the payload format or block tables.

## Commands and settings

- `/guardian transactions <x> <y> <z>` returns up to ten recent transactions in the command source's dimension. Relative coordinates work. It uses `guardian.lookup` (operator level 2 by default).
- `/guardian transactions player <name-or-uuid>` searches recent item history by UUID or a case-insensitive stored player name, using the same lookup permission. Prefer UUIDs when names change.
- `/guardian status` includes container submissions, capture failures, and backpressure counters.
- `logging.containerTransactions` is a live switch. The general and logging master switches also apply.

Back up databases before upgrading. Schemas 1 through 3 are migrated automatically; schema 4 requires this checkpoint or a newer compatible build. Flushes are asynchronous, so allow a flush interval before expecting newly queued transactions in lookup results.

## Player acceptance on each loader

1. Open a chest, barrel, hopper, shulker box, and furnace. Confirm basic take/put clicks and stack splits record their changed container, player, and cursor slots.
2. Repeat with a double chest and query either half. Confirm slot positions refer to the actual half.
3. Shift-click a stack. Confirm one transaction contains both sides, with no duplicate records after a restart.
4. Test hotbar number-key and offhand swaps, pickup-all, drag distribution, and creative clone. Check all actual item changes share the action's transaction ID.
5. Use named, damaged, enchanted, custom-data, and nested-container items. Confirm no capture failures and retained component payloads. A component-only change should be identified in lookup output.
6. Cancel a click with a protection mod, make an unchanged click, and send a stale/closed menu action. Confirm no invented successful transfer appears.
7. Stop normally and restart. Confirm history persists and the writer reports no failures.
8. Disable container logging live, repeat a click, and confirm it creates no transaction. Re-enable it and repeat.

## Close, drop, and swap acceptance on each loader

1. Pick up a stack from a chest and close the menu with items on the cursor. Query that chest and the player. Confirm one CLOSE transaction includes the cursor return and inventory changes.
2. Repeat with a full player inventory, causing the cursor stack to be dropped. Confirm one CLOSE record describes the cursor decrease without inventing an inventory destination or a duplicate nested drop record.
3. Hold a stack and press Q, then Ctrl-Q. Confirm DROP_ONE and DROP_STACK each produce one player transaction with the actual count change.
4. Press F with different items in the main and offhand slots. Confirm one SWAP_OFFHAND transaction includes both slots. Repeat with unchanged/empty slots and confirm no transaction.
5. Cancel these actions using a compatible protection mod and repeat in spectator mode. Unchanged actions must produce no transaction.
6. Restart, then query the player's UUID. Confirm the new action kinds persist on SQLite and DuckDB.

Standalone drops/swaps are associated with the player, not a block location. Menu closes retain the original container context. Nested close/drop hooks are suppressed while the outer action is being captured. Logging a removed item does not establish where a dropped entity went.

## Player inventory and creative acceptance on each loader

1. Empty the 2×2 crafting area, then move and split stacks between inventory slots, hotbar, armor, offhand, and cursor. Query the player's UUID and confirm logical inventory addresses and one transaction for each action that changes items.
2. Repeat shift-click, number-key swaps, drag distribution, pickup-all, and THROW/outside drops from the inventory screen. Confirm no duplicate standalone drop record appears.
3. Close the inventory screen with items on the cursor while crafting slots remain empty. Confirm one cursor-return transaction if vanilla changes items.
4. Place an item in the crafting grid or click its inputs/result. Confirm inventory-screen capture is skipped. Empty the grid and repeat a supported move to confirm capture resumes. Extended or replaced menu layouts must also be skipped.
5. In creative mode, replace a player inventory stack, clear it, change its count, and replace only a persistent component such as its name. Confirm CREATIVE_SET records the actual server inventory change. Repeat the same stack and confirm no duplicate/no-op transaction.
6. Test rejected creative requests, spectator/survival requests, and protection-mod cancellation. Verify unchanged inventory produces no history. Direct creative drop packets that spawn items without changing inventory are intentionally outside this slice.
7. Restart and query the player's UUID on SQLite and DuckDB. Confirm CREATIVE_SET history survives.

Inventory-screen capture reads all player inventory, armor, offhand, and cursor slots. It checks the crafting area before and after capture. It does not audit recipe inputs or output slots. Creative capture brackets the accepted `Slot.setByPlayer` invocation after vanilla scheduling and creative/slot/item checks; packet contents are never copied directly into audit history.

## Boundaries of this checkpoint

Supported menus must expose slots backed by block containers, the player's inventory, or their cursor. Menus with unknown backing inventories are skipped. Ender chests, crafting/trading menus, and entity inventories are outside this first slice. Crafting actions, extended inventory menus, direct creative drops, and automated transfers are not captured yet. Disconnect-time cursor cleanup is not claimed; the close hook covers calls to the normal server menu-close method. Logging a THROW click describes the item leaving the inventory; it does not add entity tracking.

Container rollback is not enabled. Fluids and entity logging remain outside Step 4. Dedicated idle-server smoke tests establish startup and migration behavior, not player click acceptance.
