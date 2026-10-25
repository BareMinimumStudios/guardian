# Online item rollback

Status: ownership implementation in progress. Public apply remains unavailable.

On 2026-10-24 the owner explicitly requested item transaction rollback while the server
is online. Maintenance-only operation does not satisfy this request. This opens a new
feature milestone beyond the alpha.82 release assessment.

## Bounded first implementation

Reuse the existing immutable item codec, reverse-history planner, durable full-inventory
images, source claims and save/readback protocol. Reverse complete conserving transactions,
including every source and destination. Never recreate a removed item without accounting
for its recorded counterpart. A matching preview alone does not authorize a write.

Initially target exact supported vanilla block inventories and idle online player inventories.
Do not load chunks, unpack loot, close an active player menu automatically, overwrite
conflicting contents, guess missing endpoints, or accept custom inventory subclasses.
Offline player files, entity inventories and capability-only storage are outside this first
online implementation. Registered item components must round-trip through the existing codec.

## Ownership design

The earlier lease guards cannot protect retained mutable stack/list references. The new
adapter must detach each native backing list and stack before any asynchronous journal wait.
The replacement backing image stores encoded immutable snapshots. Reads materialize fresh
stacks; ordinary list mutation throws before changing the image. Explicit adapter writes must
compare expected contents and preserve the complete inventory image and payload budgets.

`MinecraftOwnedItemStorage` implements this storage boundary. It is not installed into live
inventories yet and grants no admission authority. Its tests cover retained input lists/stacks,
count/component changes through returned values, iterator/sublist mutation, stale expected
images, malformed replacement payloads, thread confinement and ownership release.

Native installation must pin the exact container/player, every native backing list and the
player compartment aliases. Handoff must verify that those identities still belong to the
operation. Releasing ownership installs fresh mutable stacks and permanently revokes the old
view. Do not restore a stale list merely because a callback closed a session.

Detaching storage is not enough to protect the other effects of gameplay operations. Existing
caller guards must refuse enclosing transfers, player actions and lifecycle changes before
their side effects. Structural replacement, disconnect/death, revocation and shutdown must
retain unresolved journal claims. Do not silently resume a partially changed inventory.

## Completion criteria

1. Both-loader live installation/handoff tests demonstrate that retained raw references cannot
   change rollback-owned storage, including player compartment aliases and component patches.
2. Admission re-queries bounded history, checks current permissions and full owner images, and
   refuses unsupported, busy, unloaded, incomplete, claimed or conflicting transactions.
3. Apply persists full before/after images before writing, verifies actual owner saves, and
   confirms the durable receipt before successful handoff. Failure/restart tests preserve
   protection and refuse replay when storage differs from the recorded images.
4. Expose the permissioned preview/confirmation/apply command only after those runtime checks
   pass on Fabric and NeoForge. Verify component/count conservation, current permission
   revocation and interruption in isolated worlds/databases. Real-client menu presentation
   remains explicitly unverified until a client can join.

Arbitrary reflective field edits and noncooperative mod behavior are not certified by an exact
vanilla class check. Document concrete supported integration boundaries with the tested adapter.
Do not replace these criteria with another collection of generic setter guards.
