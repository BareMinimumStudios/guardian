# Item rollback

Item rollback reverses a recorded transfer between supported inventories.
It does not restore a full past inventory or recreate items that have since been
used, crafted or moved through an unsupported action.

## Start with a small transfer

1. Keep a backup of your world and Guardian database.
2. Have the player shift-click items into a nearby vanilla chest or barrel, then close it.
3. Keep that player online with menus closed, cursor empty and crafting grid empty.
   Keep both inventories inside the selected region and all containers loaded.
4. Stand near the container and run these with the player's real name:

```text
/guardian rollback-items preview u:Alex t:10m r:5
/guardian rollback-items apply u:Alex t:10m r:5
/guardian rollback-items confirm <token shown by apply>
```

Preview changes nothing. Apply also changes nothing: it selects the transfer and gives
you a token. Confirm it within 30 seconds. Guardian checks the inventories again before
changing items. If preview says zero eligible, read its specific Player/Container detail
before continuing.

Supported targets are ordinary online player inventories and vanilla chests, barrels,
hoppers, dispensers, droppers, furnaces, blast furnaces and smokers. Minecarts, direct
item displays, lectern book actions, cursor/crafting transfers and custom storage are
excluded. A completed shift-click transfer is the simplest test.

Use one included player. Item rollback does not accept multi-user or item-type filters.
There is no item redo. During apply/save checks, inventory interactions and automation
may briefly pause. Item components are preserved for supported records.

If interrupted, follow [targeted recovery](ITEM_RECOVERY.md). Do not delete journal
claims or mix a newer database with older world/player saves to bypass a refusal.

## Technical limits

- Reverse complete recorded conserving transfer chains, at most 50 source transactions,
  8 owners, 64 changed final slots and 2 MiB of full original/expected images.
- Exact supported vanilla block inventories and idle online 41-slot player inventories only.
  Refuse unloaded, sealed-loot, unknown/custom, cursor/crafting and conflicting endpoints.
- Detach native backing lists before asynchronous journal waits. Player items, armor, offhand
  and the private compartment collection all point to the owned views. The backing image is
  immutable encoded item data; reads decode fresh stacks and ordinary list writes throw.
- Audited native setters consume one exact stack/index capability. Consumption is revoked
  before later callbacks; a setter that throws after changing its slot is not silently undone.
- Persist complete before/after images and claims, then save/read back each original owner
  before marking APPLYING or admitting any slot setter. Validate native recovery encoding,
  refuse external chunk streams and cap expected saved chunk NBT at 512 KiB.
- Preserve actual I/O drain
  and compare real saved inventories before acknowledging success. Only verified completion
  returns fresh mutable native lists. Closing a failed/cancelled port keeps its ownership.
- Apply selection is read-only until its requester confirms a 30-second token. Recheck current
  base/item permissions, history and observed owners before admission. Block concurrent
  rollback/restore/purge/item checks. Internal reads/writes continue on their existing bounded
  server/history/journal executors.
- In-process reconciliation is read-only with respect to item contents. It may acknowledge an
  already complete saved outcome, never retry a partial write or guess a conflicting image.
- Before the writer starts, inspect opened storage. Cancel only unprotected PREPARED admissions.
  A remaining unfinished/protected operation requests a normal server stop before gameplay.
  Fabric's early lifecycle requires completing vanilla setup before its normal stop path.

## Acceptance evidence and limits

See [alpha.84 evidence](validation/online-items-alpha84.json). Both loaders exercise native
block/player handoff, retained and returned raw-stack aliases, all eight container types,
actual saved images, successful mutable handoff, partial interruption retention, late-save
reconciliation, requester-bound service confirmation, public commands and restart refusal.
All runtime fixtures use private synthetic players, worlds and databases, with no connected
clients. These tests do not certify client menu presentation, arbitrary mod callbacks or a
particular production pack.

There is no item redo, forced restore or automatic partial replay. Explicit maintenance
preview/confirmation can finish a proven partial protected image, including its recorded
player files, through targeted file replacements. A protected PREPARED admission can be
cancelled without item writes. See [recovery procedure and limits](ITEM_RECOVERY.md). Conflicts
remain protected for review; keep world/player saves and the journal together, and do not
remove claims to bypass protection.

Native backing ownership does not certify enclosing arbitrary mod transactions. Existing
supported vanilla caller guards exclude their side effects during retention; raw reflective
world/list replacement, unknown callers and custom storage models remain unsupported. Do not
expand this contract by adding generic setter guards or by treating an in-memory lease as proof.
