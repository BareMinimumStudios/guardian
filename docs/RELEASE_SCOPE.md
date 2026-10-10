# Release scope and acceptance

Assessed: 2026-10-09 against `0.4.0-alpha.61+1.21.1`.

This is the current release checklist. Earlier milestone notes describe implementation history; their lists of possible future work do not add requirements to this release. New work enters this checklist only when it fixes a reproducible defect in the scope below or the project owner explicitly changes the scope.

## What this release includes

Guardian is a server-only Minecraft 1.21.1 alpha for Fabric and NeoForge, using Java 21 and SQLite. Existing block lookup, inspection, bounded block rollback, correlated item history, supported vanilla crafting capture and opt-in block-hopper capture are in scope. The optional Fabric WorldEdit 7.3.8 adapter retains its separate GPL boundary. Players do not need Guardian or Fzzy Config on their client.

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
| Permissions | Vanilla operator fallback and optional provider integration exist. Actual provider and revocation acceptance remain a release gate; simulated permissions are not evidence of a tested provider. |
| Other features | Fluids, general entity history, economy/faction storage, other automation and universal CoreProtect parity are outside this release. |

## Design assessment

The current live apply design does not yet meet its exclusive-inventory contract. Reservations pin identities and observe changes, but `ItemStack.setCount` can mutate a live container stack during retention. Stack component maps and direct chunk/block-entity mutation also bypass parts of the caller contract. A whitelist of vanilla container classes does not remove this problem.

The alpha.60 and alpha.61 combined fixtures reproduce the count bypass, then restore the synthetic test data. Individual guarded callers can refuse safely before their own effects; this does not establish exclusive access for arbitrary callers or undo earlier effects in a larger operation. Cancelling a void decrement in isolation can let its caller complete a transfer without paying its source cost.

Decision: do not enable item apply in this release. Stop treating additional generic mutation guards as a release checklist. Saved-state and journal infrastructure remain internal. A later item-apply milestone needs an explicit enforceable ownership design and a bounded acceptance contract before implementation is resumed. It must preserve complete counts/components, refuse unsupported callers before effects, and reconcile actual saves and crash outcomes. This is a separate design decision, not an estimate of a few remaining patches.

## Fixed release gates

| Gate | Status | Completion evidence |
| --- | --- | --- |
| R1: automated baseline | Complete for alpha.61 | 522 tests, required clean/repeated-cache/WorldEdit builds, both-loader runtime evidence and source/package checks. The 2,912 combined fixture checks include synthetic cases and confirmed unsupported bypasses; they are not 2,912 client tests. See [alpha.61 evidence](validation/durability-alpha61.json). Re-run relevant checks if executable code changes. |
| R2: real permissions acceptance | Partial; still pending | On both loaders, verify actual provider grant, deny and revocation for lookup, inspect, block rollback and read-only item commands; verify vanilla fallback without the provider. Unauthorized output, suggestions and inspection must stop after revocation. Record provider versions and results. |
| R3: connected-client acceptance | Pending | Run the bounded matrix below on both loaders. Record server/client versions and actual pack/claim mods. The user cannot join yet; this gate waits for that opportunity. Synthetic players and idle-server startup do not close it. |
| R4: release artifact review | Prepared; final sign-off pending | Confirm the chosen version, dated changelog, loader jars, server dependencies, SQLite-only standard packaging, licenses and these limitations. No publish/push is authorized by this document. Repeat package validation for the final selected artifact. |

There are three unfinished release gates: R2, R3 and final R4 sign-off. Item apply is deferred, not a fourth release gate. A defect found during acceptance is fixed within its existing gate; it does not create a new feature requirement. Production load capacity is not certified by the controlled hopper fixtures and must not be advertised as such.

## Connected-client matrix

Use a backed-up isolated world. This is acceptance of the existing release scope, not a request to add features.

1. **Lookup and presentation:** place/break vanilla and representative pack blocks; filter by user/time/action, tab-complete parameters, page/order history, and confirm Guardian usage examples and clear hopper source/destination wording.
2. **Inspection and claims:** left-click block history and right-click container item history, including a protected claim with inspector permission. Verify reach, both hands, predicted item/block state, no duplicated result pages, and ordinary claim denial with inspection off. Revoke inspection permission while enabled and confirm access stops.
3. **Item audit:** add/remove/swap/shift-click items with distinctive persistent components; test cursor return, ordinary player actions and vanilla crafting. Verify no-op actions create no movement and unsupported menus are skipped rather than misreported.
4. **Hopper audit:** enable capture, test a successful transfer and a full destination, then restart and query history. Failed pushes must produce no transfer record; a pull into a hopper must not imply delivery to the barrel below it.
5. **Block rollback and persistence:** preview and apply a small known block rollback, verify the intended region and states, then restart and verify history. Do not use read-only item commands as item restoration.
6. **Read-only item checks:** preview/recovery/saved/cancel with valid permission, unsupported/busy endpoints and permission revocation. Verify clear refusal/skipping and no changed items, consumed source history or cleared unresolved claims. Confirm no item apply command exists.

Record pass/fail per row and loader, link any reproducible defects, and close R3 when all six rows pass. There is no fixed number of development passes promised while the external acceptance is pending.

## Permissions progress, 2026-10-10

The actual LuckPerms 5.4.140 Fabric provider passed 199 command/service checks for grant, explicit deny, regrant and grant removal across Guardian's two command aliases. The player was synthetic; its real LuckPerms attachment was initialized manually because no network login occurred. Permission decisions used the installed provider, not a simulated PermissionService.

NeoForge passed 200 command/service checks with LuckPerms 5.4.150 after its actual login listener initialized the synthetic player's attachment. The older 5.4.140 provider reproduced an uninitialized-capability failure even after explicit initialization; this matches the [upstream compatibility report](https://github.com/LuckPerms/LuckPerms/issues/4259). The replacement release is listed for 1.21.4, so these results document the tested 1.21.1 combination rather than universal compatibility.

The provider fixtures changed no Guardian executable code and left the user's servers untouched. Actual network login, inspector/async output and suggestions, full handler behavior and provider-free acceptance remain within R2/R3. R2 remains open; no release gate or feature was added. [Evidence and limitations](validation/real-provider-alpha61.json).

Provider artifacts came from the published [Fabric 1.21.1 release](https://modrinth.com/plugin/luckperms/version/v5.4.140-fabric) and [NeoForge 5.4.150 release](https://modrinth.com/plugin/luckperms/version/v5.4.150-neoforge). They are private test dependencies and are not bundled with Guardian.
