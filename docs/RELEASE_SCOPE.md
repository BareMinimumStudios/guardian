# Release scope and acceptance

Assessed: 2026-10-25 against `0.4.0-alpha.89+1.21.1`.

**The feature list is frozen.** The owner authorized this optimization and release-polish
pass after alpha.86 passed CI. No new capture category, recovery direction, UI feature or
dependency is part of this pass. Fix reproducible defects within the existing contract;
historical future-work lists do not add release requirements.

Alpha.88 fixes a reproduced R3 client-login defect: authorized command/entity/fluid history
nodes were omitted from the initial command list. Node visibility now checks current permission
without requiring UUID-map insertion that has not happened yet. Runtime/deferred history checks
retain the connected-player requirement. This is a defect fix within the frozen scope, not a
new feature. [Login regression evidence](validation/login-commands-alpha88.json).

The owner subsequently reported missing sign appearance changes, direct modded display actions,
ground pickups and confusing UI navigation. Alpha.89 addresses those specific acceptance reports.
New direct display/pickup actions remain excluded from rollback. Schema 24 preserves existing
history and prevents incompatible downgrades. [Evidence](validation/display-signs-alpha89.json).

## Current release

- Server-only Minecraft 1.21.1 on Fabric and NeoForge, Java 21, official Mojang mappings,
  Gradle 9.8.0. Standard jars bundle SQLite only. The Fabric WorldEdit adapter is optional
  and separately GPL licensed; the core remains BML licensed.
- Existing block/item history and opt-in activity, entity/frame, fluid, environmental,
  growth/decay, movement and sign-text history, with filtered lookup, inspection and export.
- Read-only item transaction UI on both loaders, using plain vanilla menu icons and fully
  virtual slots. It does not decode historical component payloads or reconstruct full past
  inventories. See the [README](../README.md#read-only-item-transaction-viewer).
- Supported filtered block rollback/restore and confirmed bounded online item rollback,
  including explicit targeted maintenance recovery for proven interrupted item operations.
  See [online item rollback](ONLINE_ITEM_ROLLBACK.md) and [item recovery](ITEM_RECOVERY.md).
- Confirmed, bounded old-history purge with recovery protection. Tests use isolated data;
  development does not delete supplied server history.

Alpha.87 reduces idle UI copies and redundant renders, improves labels/states, and makes
exact-container searches use the existing location index. It keeps the same filters, ordering,
whole-transaction results, saved-data formats and durability guarantees. Broader scopes keep
their existing query shape. No schema/config migration or new index is required.

## Fixed release gates

| Gate | Current status | Evidence / remaining action |
| --- | --- | --- |
| R1: automated baseline | Alpha.89 passes 781 local tests and required builds; alpha.87 passed GitHub CI | [Current login fix](validation/login-commands-alpha88.json), [optimization evidence](validation/polish-alpha87.json), [alpha.87 CI](https://github.com/BareMinimumStudios/guardian/actions/runs/40190105399). Check alpha.89 CI after the owner's push. |
| R2: server permissions | Recorded server-side checks pass within the tested contracts | [Actual-provider baseline](validation/exports-alpha82.json), [native UI authority checks](validation/item-ui-alpha86.json), [current regressions](validation/polish-alpha87.json). Synthetic requests are not connected-client acceptance. |
| R3: connected-client / pack acceptance | Pending | Run the six existing rows below on both loaders when a tester is available, including actual claim/mod behavior and UI visuals. The owner cannot join yet; do not repeatedly request it or replace it with synthetic evidence. |
| R4: release package and approval | Local artifact checks/package prepared; final sign-off pending | Review the final selected jars, instructions, licenses and publishing metadata; confirm platform project configuration, then obtain the owner's release approval. No push, tag or publication is authorized by this document. |

The isolated UI/query measurements are comparison fixtures, not a production TPS guarantee.
Record representative pack behavior during R3 and avoid unmeasured capacity claims. No extra
feature or fixed number of development passes is required to close these gates.

## Support boundaries

| Area | Current contract |
| --- | --- |
| Blocks and inspection | Existing registered block capture and accepted interaction hooks; lookup and inspector behavior must pass the connected-client checks below. Mod items that bypass these hooks are not universally certified. |
| Item history | Supported block-backed menus, ordinary player inventory actions, exact vanilla crafting models and optional supported block-hopper transfers. No arbitrary custom menus, capability-only storage or entity inventory promise. |
| Read-only item checks | Existing eligibility, history and identity checks apply. Unloaded, missing, sealed-loot, busy or unsupported endpoints are skipped/refused without loading chunks or unpacking loot. |
| Internal bound inventories | Exact barrel, chest, hopper, dispenser, dropper, furnace, blast furnace and smoker classes with expected sizes; exact 41-slot player inventory with current online player and idle ordinary menu. Subclasses and custom inventories are refused. Bindings alone do not authorize item apply; the online adapter must install detached native ownership. |
| Item rollback writes | Confirmed supported conserving transfers only, at most 50 source transactions, 8 inventories, 64 final changed slots and 2 MiB of full images. Confirmed targeted maintenance recovery for exact partial images; no automatic replay, force overwrite or item redo. See online/recovery contracts. |
| Permissions | Vanilla operator fallback and optional provider integration exist. Actual-provider acceptance through alpha.82 and native UI operator/revocation checks through alpha.87 are recorded separately. Network/client acceptance remains R3; simulated permissions are not evidence of a tested provider. |
| Entity history | Opt-in standard living/player deaths, tracked custom names, verified animal/horse tame transitions, inserted standard animal offspring with parent IDs, and normal/glow frame placement/display/rotation/removal history. Read-only world history, with separate query permission; confirmed old-history purge is supported, restoration is excluded. |
| Fluid history | Opt-in persistent standard player bucket placement/collection and supported waterlogging. Read-only and separately permissioned; opt-in supported scheduled flow/levels/recession and liquid-formed blocks; no arbitrary automation, cauldron/custom storage or rollback. Confirmed old-history purge is supported. |
| Fire/explosion history | Opt-in supported ignition, natural burning/extinguishing and standard explosion block changes, with known actors or unknown. Cause-filtered lookup/inspection and purge; public rollback/restore excluded for these causes. |
| Growth/decay history | Opt-in standard crop ticks, sapling/tree advancement, supported player bone meal and natural leaf decay. Unknown natural actors, direct bone-meal player attribution, cause-filtered history/purge; public recovery excluded. |
| Movement history | Opt-in verified settled piston plans and actual falling source/landing facts, correlated by UUID with block-history permission/purge. Read-only recovery; no guaranteed-complete group, forced animation or missing-chunk loads. |
| Other features | Arbitrary fluid automation/custom simulation, arbitrary entity-property tracking, economy/faction storage, other automation and universal CoreProtect parity are outside this release. |

## Connected-client matrix

Initial login must advertise permitted `commands`, `entities` and `fluids` nodes without a
deop/op workaround; confirm this after installing alpha.88 and reconnecting. This regression
belongs to the existing lookup/permission row below.

Use a backed-up isolated world. This is acceptance of the existing release scope, not a request to add features.

1. **Lookup and presentation:** place/break vanilla and representative pack blocks; filter by user/time/action, tab-complete parameters, page/order history, and confirm Guardian usage examples and clear hopper source/destination wording. Send public chat, join/leave and verify the separately permissioned chat/session histories, paging and permission revocation; private messages and disabled capture must stay absent. For command logging, verify root-only defaults, argument opt-in, private/authentication exclusions, custom exclusions, no console records, separate command-history permission and network-issued command presentation. For enabled entity capture, check death/rename/tame/birth summaries, parent identities, target UUID hover/search, actor filtering and permission revocation; disabling capture must leave existing history searchable. For enabled bucket capture, check water/lava placement and collection, waterlogging, no-op/blocked targets, Nether vaporization, action/type filters, hover details and independent permission revocation.
With environmental capture enabled, also check ignition/burning/extinguishing wording, repeated/blocked ignition, owned TNT versus creeper/unknown explosions, mob-griefing denial, `c:fire`/`c:explosion` filtering, restart persistence and explicit public recovery refusal. Portal creation must not appear as burning. These checks remain within the existing lookup/presentation row.

With growth/decay enabled, check crop age summaries, natural unknown actors versus direct bone-meal actors, sapling stage/tree placement, failed trees, persistent versus decaying leaves and restart. On NeoForge, deny both single-block bone meal and multi-block tree placement and verify restored world state with no false history; repeat ignition denial. These remain part of the existing presentation/claim row.

With movement capture enabled, check settled piston pushes/pulls, slime branches, destroyed targets, blocked/over-limit moves and loaded chunk boundaries. Verify world source/destination states, correlation hover/click/paging, sand landing versus gravel dropping on a torch, disabled/canceled actions, restart and public recovery refusal. These remain within the existing presentation/claim row.

With sign-text capture enabled, edit both sides of standing/wall/hanging signs, check filtered literal hover details and left-click inspection, no-op/waxed/claim-denied attempts, capture switches and persistence. Confirm public sign-edit recovery refuses. These are checks of the existing sign feature within the same lookup/claim rows.

2. **Inspection and claims:** left-click block history and right-click container item history, including a protected claim with inspector permission. For normal/glow frames, verify insertion, rotation, item removal versus frame breaking, exact target history, both required permissions and read-only inspector clicks. Verify reach, both hands, predicted item/block/frame state, no duplicated result pages, and ordinary claim denial with inspection off. Revoke inspection permission while enabled and confirm access stops.
3. **Item audit:** add/remove/swap/shift-click items with distinctive persistent components; test cursor return, ordinary player actions and vanilla crafting. Verify no-op actions create no movement and unsupported menus are skipped rather than misreported. Open the item transaction UI and its inspector link, compare before/after pairs, hover counts/owners, browse result/transaction/slot pages, verify modded placeholders and try every move/drop/drag action. Confirm the viewer cannot change real items and closes on permission removal. This extends the existing item-audit row.
4. **Hopper audit:** enable capture, test a successful transfer and a full destination, then restart and query history. Failed pushes must produce no transfer record; a pull into a hopper must not imply delivery to the barrel below it.
5. **Block rollback and persistence:** preview a small known block rollback, confirm no world/journal changes, then apply it separately with narrowly checked filters, verify the intended region and states, then restore/redo the same history with a preview and separate apply, check conflicts and restart again to verify flags and world state. Check the separate restore grant/denial/revocation. Repeat with multiple included/excluded users and block IDs, verify the operation ordering, and check that filtered gaps stop at live-state conflicts. Do not use read-only item commands as item restoration.
6. **Item recovery and administration:** preview/recovery/saved/cancel with valid permission, unsupported/busy endpoints and permission revocation. Verify clear refusal/skipping and no changed items, consumed source history or cleared unresolved claims. For online items, use a recent completed shift-click transfer in the isolated world: preview, apply, confirm, token suggestions, wrong-requester/permission refusal, exact count/components after saved completion, and clear interruption/reconciliation wording. Verify open-menu/unsupported/conflicting requests refuse. Synthetic checks do not replace this client acceptance. On an isolated database only, preview/cancel purge, check separate level-four permission and confirmation suggestions, confirm a known batch, and verify retained recent/recovery history after restart. Never use a production database for this acceptance exercise. For scoped purge, verify the world/type scope and whole-transaction wording before confirming. For export, check its separate level-four/category grants, client messages, generated server-local path, filters/counts, JSON/native bytes, cancellation and completed-file retention. These remain within this existing administration/read-only acceptance row.

Record pass/fail per row and loader, link any reproducible defects, and close R3 when all six rows pass. There is no fixed number of development passes promised while the external acceptance is pending.
