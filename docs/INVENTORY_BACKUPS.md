# Inventory backups and recovery

Inventory checkpoints save a player's complete normal inventory. Use them to recover an accidental clear or creative deletion, including custom names, enchantments, durability and modded item data.

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

Filters are optional. With no filters, `list` shows the newest checkpoints for everyone. Click **View** to open the read-only inventory. Hover slots for item IDs and recorded counts. The viewer uses plain icons; saved names, enchantments and other item data are kept for restoration.

## Restore an inventory

1. Choose the checkpoint from `inventory list`. Check the player and date.
2. Keep that player online. Close their menus and clear their cursor and crafting grid. Administrators using the viewer must close it too.
3. Preview, select, then confirm:

```text
/guardian inventory restore preview <checkpoint UUID>
/guardian inventory restore apply <checkpoint UUID>
/guardian inventory restore confirm <token>
```

Use the token printed by `apply` within 30 seconds. Preview and apply selection do not change items. Confirmation checks the current contents, briefly pauses inventory actions, saves the original inventory, restores the checkpoint and checks the saved player file.

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

Restart the server after changing these settings. Guardian scans at most one online player per tick and queues at most four checkpoint writes. Unchanged inventories are skipped. Open menus and protected inventories are skipped until a later scan. The logging master switch also controls capture; existing backups remain readable while capture is disabled.

`maxMiB` limits stored checkpoint payloads, not the whole database file. Recovery journals, other history, indexes and SQLite working files use additional space.

Retention removes older unused checkpoints within the configured limits. It keeps backups needed by unfinished recovery and recorded restores. If protected data fills the budget, new captures are refused visibly instead of deleting recovery data. `/guardian status` shows capture, skip, failure and timing counters. Save timings include the history queue and delivery of the result; capture timings measure the snapshot work on the server thread.

## Permissions and limits

| Action | Permission | Default operator level |
| --- | --- | --- |
| List and view checkpoints | `guardian.inventory` | 3 |
| Take a manual checkpoint | `guardian.inventory.snapshot` plus `guardian.inventory` | 3 |
| Restore, undo or reconcile | `guardian.inventory.restore` plus `guardian.inventory` | 4 |

Checkpoints include normal inventory, hotbar, armor and other vanilla persistent equipment slots supported by that exact version. They do not include the ender chest, mod-added inventory slots, cursor or crafting grid. Full stored component data is limited to 1 MiB per inventory. Lists support `u:`, `t:`, `l:1–50` and `p:1–2000`; inventory states have no radius filter.

During interrupted saves, inventories stay protected. `/guardian inventory restore reconcile` checks an operation retained in the running process. After a crash, follow [item recovery](ITEM_RECOVERY.md) for stopped-gameplay preview and targeted repair; do not delete the journal or overwrite the database. Keep regular server backups too.
