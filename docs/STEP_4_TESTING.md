# Step 4: container and player item audit

This alpha shares Fabric/NeoForge item capture: immutable item capture, action correlation, persistence, and lookup for accepted clicks and close-time cursor returns in block-backed menus, plus player inventory moves, standalone drops, offhand swaps, and accepted creative slot changes. It remains a testing checkpoint until player-driven acceptance is complete.

## What is recorded

The server hook brackets the actual `AbstractContainerMenu.clicked` invocation after vanilla packet scheduling and menu validation. It reads authoritative slots, the complete player inventory (including offhand), and cursor contents. Client-provided changed-slot maps are never used as audit data. Unchanged actions produce no transaction.

Logical container addresses retain dimension, block position, and slot. A double chest is resolved into its two backing block entities. A transaction retains its container context even when only the cursor changes, such as a creative clone. Player inventory and cursor changes use the player's UUID.

Each nonempty item is encoded at count one through Minecraft's registry-aware `SINGLE_ITEM_CODEC`. The actual count is stored separately. Persistent Data Components and explicit removals are preserved. Compound keys are ordered before encoding so equivalent custom data does not create a false change. Encodings have a format/version header and bounded compressed/decompressed sizes. A transient component patch without a persistence codec causes an explicit capture failure.

One immutable transaction enters the bounded writer queue as one entry. The SQL write commits its header, complete changed-slot payload, and container locations together. Its UUID deduplicates retries. Schema 2 added separate transaction tables. Schemas 3 through 5 guard new action names and system attribution against older readers; they do not change the payload format or block tables.

## Commands and settings

- `/guardian transactions <x> <y> <z>` returns up to ten recent transactions in the command source's dimension. Relative coordinates work. It uses `guardian.lookup` (operator level 2 by default).
- `/guardian transactions player <name-or-uuid>` searches recent item history by UUID or a case-insensitive stored player name, using the same lookup permission. Prefer UUIDs when names change.
- `/guardian status` includes player/container and hopper submissions, capture failures, and backpressure counters.
- `logging.containerTransactions` is a live switch. The general and logging master switches also apply.
- `logging.automatedContainerTransfers` enables block-to-block hopper capture. It is live and defaults to false until broader load acceptance.

Back up databases before upgrading. Schemas 1 through 4 are migrated automatically; schema 5 requires this checkpoint or a newer compatible build. Flushes are asynchronous, so allow a flush interval before expecting newly queued transactions in lookup results.

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

## Hopper acceptance on each loader and backend

1. Enable `logging.automatedContainerTransfers`. Feed named or otherwise component-bearing items from a chest into a hopper and then another chest. Query all three blocks. Each successful pull or push should have one ID and both changed inventories.
2. Repeat using double chests as source and destination. Query either half. Logical slot addresses must name the half that changed; both halves remain in context.
3. Fill the destination with incompatible full stacks. Confirm failed pushes create no history, while successful pulls into the hopper still record their actual changes. Empty source attempts must also be absent.
4. Check furnaces and other sided block containers separately; verify only vanilla-accepted slots move. Protection/modded capability paths require their own acceptance.
5. Use an unopened loot chest and confirm capture does not open it or generate loot. Its first transfer may be skipped while vanilla unpacks its loot table; subsequent settled block transfers can be recorded.
6. Test a sustained hopper stream and then a representative server hopper load. Observe queue/backpressure/failure counters and tick time. This checkpoint's small live fixture is not a large-server performance benchmark.
7. Restart and query both endpoints on SQLite and DuckDB. Confirm system attribution, item components, and transaction IDs persist. Player-name/UUID queries must exclude these system records.
8. Disable the automation switch and confirm transfer behavior continues without new hopper records.

The hook brackets full push/pull attempts, including the NeoForge capability shortcut. Snapshots contain physical block inventories; balanced changes must conserve each item ID, persistent component payload, and count from source to destination. Unbalanced changes fail capture visibly instead of inventing a completed transfer. Sealed loot containers, unloaded chunks, unknown backing inventories, loose item pickup, entity inventories, and unrelated capability storage are skipped. Nested hopper hooks are covered by the outer capture scope. Other automation mechanisms are not included.

## Boundaries of this checkpoint

Supported menus must expose slots backed by block containers, the player's inventory, or their cursor. Menus with unknown backing inventories are skipped. Ender chests, crafting/trading menus, and entity inventories are outside this checkpoint. Crafting actions, extended inventory menus, direct creative drops, and automated transfers beyond block hoppers are not captured yet. Disconnect-time cursor cleanup is not claimed; the close hook covers calls to the normal server menu-close method. Logging a THROW click describes the item leaving the inventory; it does not add entity tracking.

Container rollback is not enabled. Fluids and entity logging remain outside Step 4. Dedicated idle-server smoke tests establish startup and migration behavior, not player click acceptance.

## Inspector and command acceptance (alpha.5)

- Enable `/guardian inspect`. Left-click a barrel for block history; right-click it for item additions/removals. Inspection should cancel normal breaking/opening, with no new interaction record from the inspector itself.
- Turn inspection off. Insert 5 coal, remove 2, rearrange slots, then inspect again. Expect net container changes, without cursor duplication or transaction UUIDs in chat.
- Check `/guardian transactions user:<name> time:1h limit:20 page:1` and the short aliases. Keep the same filters and use `p:2` to reach older results. `transactions player <name-or-uuid>` remains accepted.
- Tab-complete user names while the player is online, time amounts such as `t:12`, and filter keys after an existing filter. Offline names remain valid as typed filters.
- Block lookup searches the command source dimension. Test registered modded block placements/breaks and case-insensitive player filters. Report the mod/block ID if its item overrides vanilla placement and is missed.
- Open and close a door with inspection off, then inspect either half. Test trapdoors/gates/levers/buttons, no-op uses, and protection-mod cancellation. Opening an empty menu is not currently a recorded item movement.
- Hopper history requires `logging.automatedContainerTransfers=true`. Its actor is Hopper, so it is excluded from a player's `u:` results. Check by container coordinates or right-click inspector.
- Chunk boundaries are not lookup boundaries: history is indexed by physical positions. Double-chest context indexes both halves; inspecting an unchanged half says the items changed in the other half. Live chunk-boundary transfer acceptance remains pending.
