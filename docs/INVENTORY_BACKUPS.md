# Inventory backups and recovery

Inventory checkpoints save a player's normal inventory, armor, offhand, ender chest and supported equipped items. Use them to recover an accidental clear or creative deletion, including custom names, enchantments, durability and modded item data.

They are separate from `/guardian rollback-items`, which reverses recorded transfers. Restoring a checkpoint replaces the player's inventory; it does not remove copies that were moved into other inventories. Give restore permission only to trusted administrators.

**Choose a checkpoint saved before the loss.** Taking a new checkpoint after clearing an inventory saves its empty state; it cannot recreate earlier items. If you only have transaction history, use the separate transfer rollback or recorded creative-deletion recovery commands.

## Save and browse

Automatic backups are off by default. To take a manual checkpoint, keep the player online with menus closed and nothing on their cursor or crafting grid:

```text
/guardian inventory snapshot u:Player
/guardian inventory list
/guardian inventory list u:Player t:1d l:10 p:1
/guardian inventory view <checkpoint UUID>
```

Filters are optional. With no filters, `list` shows the newest checkpoints for everyone. Click **View** to open the read-only inventory. Hover slots for item IDs and recorded counts. Use the arrows to browse ender-chest and named equipment pages. The viewer uses plain icons; saved names, enchantments and other item data are kept for restoration.

## Restore an inventory

1. Choose the checkpoint from `inventory list`. Check the player and date.
2. Keep that player online. Close their menus and clear their cursor and crafting grid. Administrators using the viewer must close it too.
3. Preview, select, then confirm:

```text
/guardian inventory restore preview <checkpoint UUID>
/guardian inventory restore apply <checkpoint UUID>
/guardian inventory restore confirm <token>
```

Use the token printed by `apply` within 30 seconds. Preview and apply selection do not change items. Confirmation checks the current contents, briefly pauses inventory actions, saves the original inventory, restores the checkpoint and verifies the actual saved inventory and equipment files.

The completion message gives a **before-restore checkpoint UUID**. To undo, run the same preview/apply/confirm commands with that UUID. Each checkpoint can be restored once; a successful restoration gives you a new before-state for undo.

If a player disconnects, opens a menu, changes items or loses the required permissions during admission, Guardian refuses the operation. Oversized or incompatible items and checkpoints from a different Minecraft version are refused rather than truncated or silently converted.

## Automatic backups

In the server's Guardian config, set:

```toml
[inventoryBackups]
automatic = true
intervalSeconds = 60
perPlayer = 128
total = 10000
maxMiB = 512
```

Restart the server after changing these settings. Guardian scans at most one online player per tick and queues at most four checkpoint writes. Unchanged inventories are skipped. Open menus and protected inventories are skipped until a later scan. This applies to every online player, without a command or permission grant for that player. Supported equipped items are captured too, including contents stored on equipped backpack and belt items. The logging master switch also controls capture; existing backups remain readable while capture is disabled.

`maxMiB` limits stored checkpoint payloads, not the whole database file. Recovery journals, other history, indexes and SQLite working files use additional space.

Retention removes older unused checkpoints within the configured limits. It keeps backups needed by unfinished recovery and recorded restores. If protected data fills the budget, new captures are refused visibly instead of deleting recovery data. `/guardian status` shows capture, skip, failure and timing counters. Save timings include the history queue and delivery of the result; capture timings measure the snapshot work on the server thread.

## Permissions and limits

| Action | Permission | Default operator level |
| --- | --- | --- |
| List and view checkpoints | `guardian.inventory` | 3 |
| Take a manual checkpoint | `guardian.inventory.snapshot` plus `guardian.inventory` | 3 |
| Restore, undo or reconcile | `guardian.inventory.restore` plus `guardian.inventory` | 4 |

Checkpoints include normal inventory, hotbar, armor, offhand, other vanilla persistent equipment slots for that Minecraft version, and the ender chest. Supported equipment adapters are **Trinkets, Curios, Accessories, Cosmetic Armor Reworked, Tool Belt, Backpacked and Traveler's Backpack**, where available for your loader/version. Equipped item data includes names, enchantments, damage and stored contents. Other mod-owned storage, cursor items and crafting-grid items are outside this coverage.

Restoring requires matching equipment mods, versions and slot layouts. Missing adapters, changed layouts and invalid native slots are refused; Guardian does not silently restore only part of an equipment checkpoint. Older normal-inventory checkpoints remain readable and restore only the slots they originally saved. Checkpoints contain items, not player health, experience or position.

The complete image is limited to 256 slots and 1 MiB of stored item component data. Lists support `u:`, `t:`, `l:1–50` and `p:1–2000`; inventory states have no radius filter.

During interrupted saves, inventories stay protected. `/guardian inventory restore reconcile` checks an operation retained in the running process. After a crash, follow [item recovery](ITEM_RECOVERY.md) for stopped-gameplay preview and targeted repair; do not delete the journal or overwrite the database. Keep regular server backups too.

## If automatic backups are missing

Run `/guardian status` and find **Inventory checkpoints**. `automatic=true` confirms the server loaded the setting. `captured` counts new saved images; `unchanged` means the existing image already matches; `skipped` means a player could not safely be captured. `lastSkip` gives the latest reason. These counters reset when the server restarts.

Try `/guardian inventory snapshot u:Player` with that player online. A manual attempt reports its exact refusal. Check `[logging] enabled = true`, close menus and clear cursor/crafting items. Changes to `[inventoryBackups]` require a server restart. A filtered list can hide existing backups; use `/guardian inventory list` to check everyone.
