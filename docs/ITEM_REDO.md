# Redo an item rollback

Item redo puts items back where they were before a completed transfer rollback.
It uses that rollback's saved operation UUID, not a new player/time/radius search.

## Commands

1. Keep a backup. Keep the affected players online with menus closed, cursor empty
   and crafting grid empty. Load all involved containers.
2. Run `/guardian restore-items` to list recent completed, saved transfer rollbacks.
   You can also use the UUID in the original rollback's completion message.
3. Preview that exact operation:

```text
/guardian restore-items preview <operation UUID>
```

Press Tab after `preview` or `apply` to choose a recent operation UUID.

4. If eligible, select it and confirm the returned token within 30 seconds:

```text
/guardian restore-items apply <operation UUID>
/guardian restore-items confirm <token>
```

Preview and apply selection change nothing. Confirmation checks the history and inventories
again before changing items. Permission is `guardian.restore` plus `guardian.restore.items`,
or operator level 3.

## Why Guardian may refuse

Every slot in each involved inventory must still match the saved rollback result, including
unchanged slots. Newer item history, a changed block, another reserved inventory, an offline
player, an open menu or an unloaded container can prevent redo. Leave the inventories alone
between rollback and redo. There is no force option.

Each completed transfer rollback can be redone once. Redo cannot reverse creative-deletion
recovery, redo another redo, recreate unlogged items, or restore a whole historical inventory.
It supports the same player and vanilla block inventories and limits as
[item rollback](ONLINE_ITEM_ROLLBACK.md). The recent list shows saved candidates, not a promise
that they are still eligible.

## If interrupted

Use `/guardian restore-items reconcile` only after an operation reports that reconciliation
is needed. It checks actual saved results; it does not repeat partial writes.
`/guardian restore-items cancel` cancels selection or requests safe retention after admission.
A partial operation keeps its inventories protected. Follow the existing
[targeted recovery procedure](ITEM_RECOVERY.md) using the redo operation UUID.
Do not remove claims or restore only part of the world/database backup.

## Updating

Alpha.100 upgrades the database to schema 28. Existing transfer and creative-recovery
journals retain their purpose and receipts. Back up before upgrading; older jars cannot
open the upgraded database. Configuration version 19 is unchanged.
