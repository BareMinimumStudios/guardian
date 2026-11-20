# Recover deleted creative items

Alpha.99 is a testing build. Keep a server backup before updating.

Guardian can recover items deleted through supported creative inventory actions when
it recorded the original item and the resulting empty slot. This is separate from
`rollback-items`, which reverses transfers between inventories.

## Recover a deletion

1. Keep the affected player online. Close their menus and empty their cursor and crafting grid.
2. Leave the deleted inventory slots empty. Stop moving items during the operation.
3. Preview a short time window:
   `/guardian recover-items preview u:Alex t:10m`
4. If the preview is eligible, select the same window:
   `/guardian recover-items apply u:Alex t:10m`
5. Run `/guardian recover-items confirm <token>` with the token shown by apply within 30 seconds.

Preview and apply selection do not change items. Confirmation checks the live inventory
again before restoring the recorded stacks. Item names, enchantments, damage and other
recorded data components are preserved. One operation can select up to 50 transactions;
shorten the window if too much history matches. A previously recovered deletion cannot
be recovered again.

## Requirements and limits

- Permission: `guardian.recover.items` plus `guardian.rollback`, or operator level 3.
- One online player and a time window are required. Supported filters are `u:` / `user:`
  and `t:` / `time:`. Deleted inventory slots have no radius or world location.
- Only recorded item-to-empty creative deletions qualify. Replacements, partial stack
  changes, consumed items and arbitrary missing items do not.
- Occupied slots, ambiguous repeated changes, newer conflicting history, unsupported
  ownership and unreadable item data are refused. There is no force overwrite.
- This is not a historical inventory backup. It cannot recover events Guardian did not log.

## If interrupted

Use `/guardian recover-items reconcile` for a retained live operation, following the
message shown. `/guardian recover-items cancel` requests cancellation; it does not undo
partial writes or remove recovery protection. If saved-file repair is required, follow
the [interrupted-operation recovery guide](ITEM_RECOVERY.md). Never delete the journal
to force the server to start. Use the same Minecraft version and modpack for repair.

The recovery journal records creative recovery separately from transfer rollback.
Updating an older database preserves existing transfer journals and protected images.
Back up the database before upgrading; older Guardian builds cannot open the new schema.
