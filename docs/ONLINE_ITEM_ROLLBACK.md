# Online item rollback

Alpha.84 implements the bounded online ownership and command milestone explicitly requested
on 2026-10-24. Maintenance-only operation does not satisfy that request. See the README for
commands, operational limits and the restart/backup requirement.

## Implemented contract

- Reverse complete recorded conserving transfer chains, at most 50 source transactions,
  8 owners, 64 changed final slots and 2 MiB of full original/expected images.
- Exact supported vanilla block inventories and idle online 41-slot player inventories only.
  Refuse unloaded, sealed-loot, unknown/custom, cursor/crafting and conflicting endpoints.
- Detach native backing lists before asynchronous journal waits. Player items, armor, offhand
  and the private compartment collection all point to the owned views. The backing image is
  immutable encoded item data; reads decode fresh stacks and ordinary list writes throw.
- Audited native setters consume one exact stack/index capability. Consumption is revoked
  before later callbacks; a setter that throws after changing its slot is not silently undone.
- Persist complete before/after images and claims before writing; preserve actual I/O drain
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

There is no item redo, forced restore, offline player editing, automatic partial replay or
offline repair utility. A partial/conflicting protected operation needs a consistent backup
or separately reviewed data recovery before gameplay can resume. Keep world/player saves
and the journal together; do not remove claims to bypass protection.

Native backing ownership does not certify enclosing arbitrary mod transactions. Existing
supported vanilla caller guards exclude their side effects during retention; raw reflective
world/list replacement, unknown callers and custom storage models remain unsupported. Do not
expand this contract by adding generic setter guards or by treating an in-memory lease as proof.
