# Release scope and acceptance

Assessed: 2026-10-24 against `0.4.0-alpha.69+1.21.1`.

This is the current release checklist. Earlier milestone notes describe implementation history; their lists of possible future work do not add requirements to this release. New work enters this checklist only when it fixes a reproducible defect in the scope below or the project owner explicitly changes the scope.

## Authorized command logging milestone

On 2026-10-24, after alpha.68 passed CI, the owner selected bounded opt-in command logging. This includes independent command capture/history permissions, root-only capture by default, optional permitted arguments, sensitive/custom-root exclusions, and a shared post-parse hook on both loaders. It records admission rather than success, and does not add console/function tracing, private-message capture or universal pack-cancellation guarantees. Existing item-apply exclusions stand. No supplied server database is modified for development tests.

## Authorized purge milestone

On 2026-10-24 the owner explicitly approved purge for old block, item, chat and session history, with preview, confirmation, a 1,000-record limit and recovery protection. All development purge tests use isolated databases. This milestone includes fixed-key batches, a requester-bound expiring token, current permission checks, conservative journal retention and a durable history-gap marker. No actual supplied server data is purged. Item apply, automatic retention jobs and new capture categories remain outside scope.

## Authorized block restore/redo milestone

On 2026-10-23 the owner explicitly selected block restore/redo next. This addition includes filtered chronological replay of previously rolled-back block records, a read-only preview, a separate restore permission and directional pending journal state. It reuses the bounded block executor and does not enable item apply, purge or additional logging categories. Restore refers to original history filters, not a last-command undo token. Network/client acceptance joins the existing R3 block row.

## Authorized block-preview milestone

After pushing the history-filter milestone, the owner asked to continue the recommended sequence. This pass adds bounded read-only block rollback preview with the same single-user apply filters. No claims or world changes occur during preview. Separate apply re-queries and revalidates; preview is not an exclusive snapshot, reservation or guarantee. General restore/redo, purge and item apply are outside this pass.

## Authorized history-filter milestone

The owner explicitly chose richer history search after the activity milestone: multi-user inclusion/exclusion, block/item resource inclusion/exclusion and matching tab completion. This is read-only search work. Rollback and item preview refuse the extended filters and retain their earlier single-user contract. Purge, rollback preview/redo and item application were not authorized in this pass.

## Authorized activity milestone

The owner explicitly added public chat and login/logout logging on 2026-10-17. This bounded addition includes opt-in public chat, separately configurable session events and separately permissioned, paged user/time searches. It excludes command/private-message logging and session replay. Existing item-apply exclusions stand. Actual network session/chat and pack cancellation behavior join the existing connected-client acceptance; synthetic checks do not certify those paths.

## What this release includes

Guardian is a server-only Minecraft 1.21.1 alpha for Fabric and NeoForge, using Java 21 and SQLite. Existing block lookup, inspection, bounded block rollback/restore, bounded confirmed history purge, correlated item history, supported vanilla crafting capture and opt-in block-hopper capture are in scope. The optional Fabric WorldEdit 7.3.8 adapter retains its separate GPL boundary. Players do not need Guardian or Fzzy Config on their client.

Item rollback commands remain read-only: preview, recovery comparison, saved-data comparison and cancellation of those checks. An eligible preview is an observation, not a promise that applying it is safe. Cancellation does not restore items or release unresolved recovery claims. There is no item apply command.

Capture support does not imply rollback support. Block-backed menu capture follows the documented backing-slot rules; unknown or extended menu/storage models are skipped. Hopper records describe observed balanced source/destination changes, not a promise of delivery beyond the recorded endpoints. Missing captures and backpressure/failure counters must remain visible; this alpha does not promise every mutation from every mod is logged.

## Support boundaries

| Area | Current contract |
| --- | --- |
| Blocks and inspection | Existing registered block capture and accepted interaction hooks; lookup and inspector behavior must pass the connected-client checks below. Mod items that bypass these hooks are not universally certified. |
| Item history | Supported block-backed menus, ordinary player inventory actions, exact vanilla crafting models and optional supported block-hopper transfers. No arbitrary custom menus, capability-only storage or entity inventory promise. |
| Read-only item checks | Existing eligibility, history and identity checks apply. Unloaded, missing, sealed-loot, busy or unsupported endpoints are skipped/refused without loading chunks or unpacking loot. |
| Internal bound inventories | Exact barrel, chest, hopper, dispenser, dropper, furnace, blast furnace and smoker classes with expected sizes; exact 41-slot player inventory with current online player and idle ordinary menu. Subclasses and custom inventories are refused. These internal bindings do not authorize item apply. |
| Item rollback writes | No supported public write scope. All item apply is unavailable, including vanilla-only worlds. |
| Permissions | Vanilla operator fallback and optional provider integration exist. Server-side actual-provider and revocation acceptance passed in alpha.62. Network/client acceptance remains R3; simulated permissions are not evidence of a tested provider. |
| Other features | Fluids, general entity history, economy/faction storage, other automation and universal CoreProtect parity are outside this release. |

## Design assessment

The current live apply design does not yet meet its exclusive-inventory contract. Reservations pin identities and observe changes, but `ItemStack.setCount` can mutate a live container stack during retention. Stack component maps and direct chunk/block-entity mutation also bypass parts of the caller contract. A whitelist of vanilla container classes does not remove this problem.

The alpha.60 and alpha.61 combined fixtures reproduce the count bypass, then restore the synthetic test data. Individual guarded callers can refuse safely before their own effects; this does not establish exclusive access for arbitrary callers or undo earlier effects in a larger operation. Cancelling a void decrement in isolation can let its caller complete a transfer without paying its source cost.

Decision: do not enable item apply in this release. Stop treating additional generic mutation guards as a release checklist. Saved-state and journal infrastructure remain internal. A later item-apply milestone needs an explicit enforceable ownership design and a bounded acceptance contract before implementation is resumed. It must preserve complete counts/components, refuse unsupported callers before effects, and reconcile actual saves and crash outcomes. This is a separate design decision, not an estimate of a few remaining patches.

## Fixed release gates

| Gate | Status | Completion evidence |
| --- | --- | --- |
| R1: automated baseline | Complete locally for alpha.69; remote rerun pending (alpha.68 CI passed) | 624 tests, both-loader command capture/query/restart and 1,636 actual-provider/operator checks; see [command evidence](validation/commands-alpha69.json). Prior 611 tests, 30 isolated purge cases and 418 actual-provider/operator checks; see [purge evidence](validation/purge-alpha68.json). Prior 597 tests, 56 restore cases and actual-provider/operator acceptance; see [restore evidence](validation/restore-alpha67.json). Prior 589 tests and 18 preview/apply runtime cases; see [preview evidence](validation/block-preview-alpha66.json). Prior 561 tests and 18 isolated history command cases; see [filter evidence](validation/filters-alpha65.json). Prior 551 tests and both-loader activity fixtures; see [activity evidence](validation/activity-alpha64.json). Prior 546 fresh tests and required clean/repeated-cache/WorldEdit builds; see [CI correction](validation/ci-alpha63.json). Prior both-loader permissions/runtime evidence and package checks. The prior 2,912 combined alpha.61 fixture checks remain historical baseline evidence, including unsupported bypasses. See [alpha.62 evidence](validation/permissions-alpha62.json) and [alpha.61 evidence](validation/durability-alpha61.json). Re-run relevant checks if executable code changes. |
| R2: real permissions acceptance | Server-side checks complete through alpha.69; network/client acceptance remains R3 | Current command permissions passed 1,636 actual-provider/operator checks; see [command evidence](validation/commands-alpha69.json). Prior purge permissions passed 418 actual-provider/operator checks; see [purge evidence](validation/purge-alpha68.json). Prior restore preview/apply permissions passed 2,420 actual-provider/operator checks; see [restore evidence](validation/restore-alpha67.json). Prior activity/preview permissions passed 2,068 actual-provider/operator checks; see [alpha.66 acceptance](validation/acceptance-alpha66.json). Prior real LuckPerms grant, deny and revocation on both loaders, queued lookup/inspector output, username/time/UUID suggestions, read-only item checks, persistent block rollback boundaries and provider-free actual operator entries passed 1,150 checks. Synthetic players and packet queues do not certify client presentation or network login; those remain R3. See [evidence and limits](validation/permissions-alpha62.json). |
| R3: connected-client acceptance | Pending | Run the bounded matrix below on both loaders. Record server/client versions and actual pack/claim mods. The user cannot join yet; this gate waits for that opportunity. Synthetic players and idle-server startup do not close it. |
| R4: release artifact review | Prepared; final sign-off pending | Alpha.69 local artifact checks passed; prior alpha.67 installation/test-package review passed; see [package evidence](validation/artifacts-alpha67.json); see [artifact review](VALIDATION.md). Final sign-off still waits for connected-client results and the owner’s release decision. No publish/push is authorized by this document. Repeat package validation for the final selected artifact. |

Server-side permissions acceptance for activity, preview, restore, purge and command additions is complete within R2. R3 connected-client acceptance and final R4 sign-off remain pending. These are checks of the added feature, not new feature requirements. Purge client presentation joins R3 on an isolated database; it does not authorize deletion of supplied server history during development. Item apply is deferred and does not add a release gate. A defect found during acceptance is fixed within its existing gate; it does not create a new feature requirement. Production load capacity is not certified by the controlled hopper fixtures and must not be advertised as such.

## Connected-client matrix

Use a backed-up isolated world. This is acceptance of the existing release scope, not a request to add features.

1. **Lookup and presentation:** place/break vanilla and representative pack blocks; filter by user/time/action, tab-complete parameters, page/order history, and confirm Guardian usage examples and clear hopper source/destination wording. Send public chat, join/leave and verify the separately permissioned chat/session histories, paging and permission revocation; private messages and disabled capture must stay absent. For command logging, verify root-only defaults, argument opt-in, private/authentication exclusions, custom exclusions, no console records, separate command-history permission and network-issued command presentation.
2. **Inspection and claims:** left-click block history and right-click container item history, including a protected claim with inspector permission. Verify reach, both hands, predicted item/block state, no duplicated result pages, and ordinary claim denial with inspection off. Revoke inspection permission while enabled and confirm access stops.
3. **Item audit:** add/remove/swap/shift-click items with distinctive persistent components; test cursor return, ordinary player actions and vanilla crafting. Verify no-op actions create no movement and unsupported menus are skipped rather than misreported.
4. **Hopper audit:** enable capture, test a successful transfer and a full destination, then restart and query history. Failed pushes must produce no transfer record; a pull into a hopper must not imply delivery to the barrel below it.
5. **Block rollback and persistence:** preview a small known block rollback, confirm no world/journal changes, then apply it separately with narrowly checked filters, verify the intended region and states, then restore/redo the same history with a preview and separate apply, check conflicts and restart again to verify flags and world state. Check the separate restore grant/denial/revocation. Do not use read-only item commands as item restoration.
6. **Read-only item checks:** preview/recovery/saved/cancel with valid permission, unsupported/busy endpoints and permission revocation. Verify clear refusal/skipping and no changed items, consumed source history or cleared unresolved claims. Confirm no item apply command exists. On an isolated database only, preview/cancel purge, check separate level-four permission and confirmation suggestions, confirm a known batch, and verify retained recent/recovery history after restart. Never use a production database for this acceptance exercise.

Record pass/fail per row and loader, link any reproducible defects, and close R3 when all six rows pass. There is no fixed number of development passes promised while the external acceptance is pending.
