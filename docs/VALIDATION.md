# Guardian bulk and hotbar protection checkpoint

Date: 2026-10-09. Checkpoint: `0.4.0-alpha.40+1.21.1`.

- All 331 existing tests passed: 282 common and 49 Minecraft tests. This Minecraft API change adds runtime coverage rather than mirrored unit tests.
- Fabric and NeoForge each passed 275 live checks with logging off/on: 1,100 total. Eleven new checks per run exercised hotbar selection, swaps and bulk calls against a started journal request. Full 41-slot images, selected index, damaged-item components, extra-container image and cursor image stayed unchanged. Bulk limits -1, 1 and 0 returned zero without evaluating a throwing predicate. Revoked retention kept protecting all three inventories. After actual worker drain, count-only queries returned the correct combined item count, hotbar operations resumed with exact components, and bounded clearing removed the expected item count.
- The prior 264 checks per run passed again, including direct player/block guards, exact audited setter chains, ordered uninstall refusal, 36 complete actual saved-owner readbacks and twelve SQLite recovery journals preserving both owner claims, the source claim and original audit row after reopening. Temporary test databases produced zero container records.
- Normal restarts without agents passed both loaders, returned expected historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both dedicated servers are stopped with alpha.40 installed; original configurations were restored and synthetic players, test blocks and forced chunks were cleaned up. The optional Fabric WorldEdit adapter matches alpha.40.
- Clean build, repeated build with configuration-cache reuse and separate WorldEdit build passed through IDEA with Java 21 and Gradle 9.8.0. IDEA found no problems in the changed mixin. MixinMCP verified the mutation order on both loaders and traced insertion callers. Schema 8, config 6 and audit formats remain unchanged; standard packaging stays SQLite-only. See [validation data](validation/bulk-hotbar-retention-alpha40.json).

Production code still does not register retention or enable item apply. During pending protection, a bulk return value of zero means refusal before work, not a certified empty inventory; count-only behavior outside retention is unchanged. Insertion still needs coordinated menu cleanup: placeItemBackInInventory splits its input before add, and AbstractContainerMenu.removed clears the cursor unconditionally after the return attempt. Cancelling either API alone can lose items. Other insertion callers, lifecycle/data mutation paths, scheduled shutdown, trusted live apply/save integration, persistent outcome reconciliation, raw/off-thread mutation coverage and connected-client synchronization remain pending. Item rollback apply remains disabled.

## Historical alpha.39 player slot protection checkpoint


Date: 2026-10-09. Checkpoint: `0.4.0-alpha.39+1.21.1`.

- All 331 existing tests passed: 282 common and 49 Minecraft tests. This Minecraft API change adds runtime coverage rather than mirrored unit tests.
- Fabric and NeoForge each passed 264 live checks with logging off/on: 1,056 total. Ten new checks per run exercised direct player setters and removals against a started journal request. Full 41-slot images and the offered stack stayed unchanged; count, no-update and selected-slot removals returned empty results; identity-based removal was refused. Audited player setters remained usable. Revoked retention continued blocking ordinary writes and normal writes/removals resumed after actual worker drain. Unrelated block writes remained conservatively refused during player retention.
- The prior 254 checks per run passed again, including block guards, ordered uninstall refusal, 36 complete actual saved-owner readbacks, and twelve SQLite recovery journals preserving both owner claims, the source claim and original audit row after reopening. Temporary test databases produced zero container records.
- Normal restarts without agents passed both loaders, returned expected historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both dedicated servers are stopped with alpha.39 installed; original configurations were restored and synthetic players, test blocks and forced chunks were cleaned up. The optional Fabric WorldEdit adapter matches alpha.39.
- Clean build, repeated build with configuration-cache reuse and separate WorldEdit build passed through IDEA with Java 21 and Gradle 9.8.0. IDEA found no problems in the changed mixin or bridge. MixinMCP verified vanilla/NeoForge method signatures and mutation ordering. Schema 8, config 6 and audit formats remain unchanged; standard packaging stays SQLite-only. See [validation data](validation/player-slot-retention-alpha39.json).

Production code still does not register retention or enable item apply. Composite inventory insertion and bulk operations, lifecycle/data mutation paths, scheduled shutdown, trusted live apply/save integration, persistent outcome reconciliation, raw/off-thread mutation coverage and connected-client synchronization remain pending. Minecraft splits an offered stack before its placeItemBackInInventory insertion attempt, so an add-only refusal would be unsafe; this milestone does not add that incomplete guard. Item rollback apply remains disabled.

## Historical alpha.38 block-retention guard checkpoint


Date: 2026-10-09. Checkpoint: `0.4.0-alpha.38+1.21.1`.

- All 331 tests passed: 282 common and 49 Minecraft tests. The new retention query covers active, revoked and stopped entries through actual worker drain; a second policy test denies unrelated transfers and menus before endpoint resolution; prior registry, worker and SQLite suites passed again.
- Fabric and NeoForge each passed 254 live checks with logging off/on: 1,016 total. Eight supported physical inventories refused ordinary setItem, removal, no-update removal and clear calls while a journal read was running. Full slot/component images remained unchanged; removals returned ItemStack.EMPTY. Unrelated block setters were conservatively refused too. Five randomized container types refused loot seed writes. Transfer/menu policies refused unrelated operations before resolving endpoints; live checks paused unrelated furnace ticks and player menu actions during protection, avoiding partial processing around denied block APIs.
- Exact audited ticket chains remained usable during retention. Revoked retained entries continued blocking ordinary writes until drain; writes resumed afterwards. Shutdown checks left the binding and pinned sessions intact, legacy uninstall refused premature detachment, and nonblocking uninstall succeeded after drain. Existing shutdown callback protections passed again.
- The prior 172 checks per run passed again, including 36 actual complete owner save/readbacks. Twelve final private SQLite journal scenarios reopened as RECOVERY_REQUIRED with both owner claims, source claim and original audit row retained. Test databases produced zero container records. The first test attempt used the player removal mapping for a container; MixinMCP verified the corrected Container.removeItem mapping before rerunning. Its three journal fixtures and log remain in the private archive. An earlier successful matrix and its twelve journals were preserved before adding the unrelated-operation guard checks and repeating the final matrix.
- Normal restarts without agents passed both loaders, returned expected historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both dedicated servers are stopped with alpha.38 installed, original configurations restored, synthetic player files archived and test blocks/tickets removed. The optional Fabric WorldEdit adapter matches alpha.38.
- Clean build, repeated build with configuration-cache reuse and separate WorldEdit build passed through IDEA with Java 21 and Gradle 9.8.0. IDEA reported no problems in all four changed mixins, the bridge, registry, transfer/menu policies and new tests. MixinMCP verified vanilla mutation ordering and signatures in Fabric/NeoForge sources. Schema 8, config 6 and audit formats remain unchanged; standard packaging stays SQLite-only. Private guidance, agents, tooling, logs, databases and archives remain outside Git. See [validation data](validation/block-retention-guards-alpha38.json).

Production code still does not register retention or enable item apply. This main-thread block API slice does not cover player/lifecycle/data mutations, raw stacks/lists, unsupported overrides or off-thread writes. Scheduling shutdown retries, trusted apply/save integration, persistent outcome reconciliation and connected-client synchronization remain pending. Item rollback apply remains disabled.

## Historical alpha.37 journal owner-retention checkpoint


Date: 2026-10-08. Checkpoint: `0.4.0-alpha.37+1.21.1`.

- All 329 tests passed: 280 common and 49 Minecraft tests. Eight new registry tests exercise expiry, whole-operation invalidation, close/stop, thread confinement, foreign-worker/duplicate registration, drain polling, active lease lifecycle and capacity limits. One SQLite test exercises delayed commits with successful and lost acknowledgements in two isolated databases.
- Expired/revoked retained leases reject write permits and overlapping acquisitions while started journal work is pending. Registry entries detach only after the owning thread observes drain; no worker callback mutates the registry. A still-active lease retains its original deadline and normal close behavior after an early drain.
- The SQLite tests held expired owner entries through an actual delayed completion and restart. Both successful and failed acknowledgements reopened as COMPLETED after the real SQLite commit, with owner claims released and source claims retained. This demonstrates journal/registry lifetime handling, not physical Minecraft exclusion.
- Fabric and NeoForge each passed the existing 172 live inventory/setter/journal/save checks with logging off/on: 688 regression checks total. Thirty-six actual owner save/readbacks again matched full inventories and components. Twelve isolated journal scenarios reopened as RECOVERY_REQUIRED with both owner claims, source claim and original audit row retained. Test databases produced zero container records.
- Normal restarts without agents passed both loaders, returned expected historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both dedicated servers are stopped with alpha.37 installed, original configs restored, synthetic player files archived and test blocks/tickets removed. The matching optional Fabric WorldEdit adapter is present.
- Clean build, repeated build with configuration-cache reuse and separate WorldEdit build passed through IDEA with Java 21 and Gradle 9.8.0. IDEA reported no problems in the changed registry and new tests. Schema 8, config 6 and audit formats remain unchanged; standard packaging stays SQLite-only. Private guidance, agents, tooling, logs, databases and source archives remain outside Git. See [validation data](validation/journal-owner-retention-alpha37.json).

No live code registers journal retention yet. Hooks that invalidate and then permit normal writes, binding detachment at shutdown, raw/off-thread mutations, persistent outcome reconciliation and client synchronization still need production integration. Keeping registry entries does not establish complete exclusion. Item rollback apply remains disabled.

## Historical alpha.36 journal worker shutdown checkpoint


Date: 2026-10-08. Checkpoint: `0.4.0-alpha.36+1.21.1`.

- All 320 tests passed: 271 common and 49 Minecraft tests. Ten new worker lifecycle tests cover both journal protocols, queue saturation, rejection at shutdown, uninterruptible running operations, callback reentry, caller cancellation, failed results, record validation, thread confinement and idempotent close. Three SQLite tests exercise four isolated database fixtures.
- SQLite queued completion requests rejected at shutdown retain both owner claims and reopen as RECOVERY_REQUIRED. Already-running completion writes can finish after close and reopen as COMPLETED. A simulated acknowledgement failure after the actual SQLite commit still reopens as COMPLETED. Completed records release owner claims and retain their source claim; unresolved records retain both.
- The full asynchronous save-completion protocol completed through the bounded worker and actual SQLite journal with controlled save results and inventory calls on the driver thread. This does not certify Minecraft exclusion or world/player saved data.
- Final clean build, repeated build with configuration-cache reuse and separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0. IDEA reported no problems in the worker and both test files after explicit callback types resolved an IDE annotation inference issue. Standard loader artifacts package the shared worker and remain SQLite-only.
- No new dedicated-server deployment or live matrix was performed for this dormant common protocol work. Both servers remain stopped with the previously validated alpha.34 artifacts. Schema 8, config 6 and audit formats remain unchanged. Private instructions, tooling, logs and archives stay outside Git. See [validation data](validation/journal-worker-alpha36.json).

Draining a worker does not establish commit success. Running results and persistent phases must be reconciled before backend disposal and protection release. Current ten-second reservations cannot supply complete exclusion through a pending commit. Production bound-session orchestration, stronger mutation exclusion and connected-client synchronization remain pending. Item rollback apply stays disabled.

## Historical alpha.35 asynchronous save-completion checkpoint


Date: 2026-10-08. Checkpoint: `0.4.0-alpha.35+1.21.1`.

- All 307 tests passed: 258 common and 49 Minecraft tests. Thirteen new protocol tests cover worker journal access, main-thread inventory calls, pending reads, full-record mismatches, bad saved contents, absent exclusivity, completion acknowledgement/readback, failures, thread confinement and late commits after stop/timeout.
- Clean build, repeated build with configuration-cache reuse and separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0. Source/test inspections reported no problems. Standard Fabric and NeoForge artifacts include the shared coordinator and SQLite-only packaging.
- The coordinator polls asynchronous journal requests and inventory saves without blocking the driver thread. A successful completion requires all owner saved results, matching journal records, an acknowledged phase transition and a matching COMPLETED record read afterwards.
- Submitted completion attempts remain observable after stopping. No cancellation or retry hides a possible persistent commit. Hosts must keep exclusive coordination until pending completion settles and reconcile its persistent outcome.
- This is common protocol work. No new live-server matrix or alpha.35 deployment was performed. The stopped dedicated servers retain the alpha.34 artifacts and prior validated configuration. Schema 8, config 6 and audit formats remain unchanged. Private guidance, tooling, logs and archives stay outside Git. See [validation data](validation/async-save-alpha35.json).

Complete mutation exclusion, production session/driver orchestration with shutdown reconciliation and connected-client synchronization remain pending. Bound-session identity checks do not certify exclusivity. Item rollback apply remains disabled.

## Historical alpha.34 bound inventory and actual save checkpoint


Date: 2026-10-08. Checkpoint: `0.4.0-alpha.34+1.21.1`.

- All 294 tests passed: 245 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0. The final clean build reported no compiler warnings. IDEA reported no problems in the session, bridge and two save invokers after refresh/build.
- Fabric and NeoForge each passed 172 live checks with logging off/on: 688 total. The setter, ticket and journal matrices passed again. Fifty-seven additional checks per run exercised actual bound sessions and saves.
- All eight supported physical block inventory types and a registered synthetic player bound complete slot layouts, wrote exact component-aware snapshots and refused off-thread access. Thirty-six actual owner saves across the four runs matched full live inventory images and damage components after disk readback. Closed sessions refused reuse while leaving lease disposal to the caller.
- Duplicate bindings, foreign/released leases, unloaded owners, sealed loot and brewing inventories were refused. Direct stack changes during readback failed both block and player results. Closing a session or invalidating its lease rejected pending results.
- Shutdown tests stopped the registry and failed pending readback, then attempted rebinding from the completion callback. Stopping coordination before notifying callbacks prevented new sessions during shutdown. Private agents replaced no Minecraft bytecode; asynchronous continuations returned control to the server while storage work completed.
- Twelve separate SQLite journal scenarios again reopened as RECOVERY_REQUIRED with both owner claims, their source claim and original audit row retained. Main isolated test databases produced zero container records.
- Normal restarts without agents passed both loaders, returned expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both dedicated servers are stopped with alpha.34 installed, original configs restored, synthetic player files archived, test blocks/tickets removed and the matching optional Fabric WorldEdit adapter present.
- Schema 8, config 6 and audit formats are unchanged. Standard artifacts remain SQLite-only. Guidance, agents, tooling, logs and databases stay outside Git and source archives. See [validation data](validation/bound-inventories-alpha34.json).

The session proves pinned identities and matching saved inventory images, not complete mutation exclusion. It never transitions journals or clears claims. Saves may still finish after a session closes or a later check fails. Exclusive apply/save ports, asynchronous save/journal completion orchestration, connected-client synchronization and remaining mutation coverage are pending. Item rollback apply stays disabled.

## Historical alpha.33 journal-confirmed apply checkpoint

# Guardian journal-confirmed apply checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.33+1.21.1`.

- All 294 tests passed: 245 common and 49 Minecraft tests. Twenty-one new contracts cover journal barriers/failures, full live images, unchanged slots, partial setters, ownership changes, timeouts, stop/late results, net no-op histories and worker/thread rules.
- Clean build, repeated build with configuration-cache reuse and separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0. The final clean build reported no compiler warnings. IDEA reported no problems in the new driver, adapter and tests.
- Fabric and NeoForge each passed 115 live checks with logging off/on: 460 total. The existing setter and ticket matrix passed again. Twelve additional checks per run connected the common driver, real Minecraft setters and private SQLite journals through a controlled test apply port.
- Three SQLite scenarios per run verified committed APPLYING before any setter, normal written contents, failure after the first actual setter, and unrelated mutation before writing. Only successful writes published the save-handoff record. Every scenario reopened as RECOVERY_REQUIRED; twelve private journals retained both owner claims, their source claim and unchanged source audit row.
- Live scenarios used a synthetic controlled port while ticking was frozen. This is not a production Minecraft exclusivity implementation or saved-state certification. Private agents replaced no Minecraft bytecode.
- Normal restarts without agents passed both loaders, returned expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both servers are stopped with alpha.33 installed, original configs restored, synthetic player files archived, test blocks/tickets removed and the matching optional Fabric WorldEdit adapter present.
- Schema 8, config 6 and audit formats are unchanged. Isolated main test databases produced zero container records; deliberately seeded journal-scenario databases are separate private files. Standard artifacts remain SQLite-only. Guidance, agents, tooling, logs and databases stay outside Git and source archives. See [validation data](validation/journal-apply-alpha33.json).

WRITTEN does not mean saved or completed. A setter may change items before throwing; the driver neither undoes nor replays partial changes. Pending journal work is not cancelled on stop. Production apply/save ports, full-owner preflight, remaining mutation exclusion, connected-client synchronization and actual saved-state completion remain pending. Commands never invoke this driver; item rollback apply stays disabled.

## Historical alpha.32 reserved setter checkpoint

# Guardian reserved setter checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.32+1.21.1`.

- All 273 tests passed: 224 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0. The clean build reported no compiler warnings.
- IDEA reported no problems in the new writer, changed bridge and five setter mixins. Both loader artifacts package the writer and setter entry types.
- Fabric and NeoForge each passed 103 live checks with logging off/on: 412 total. All eight supported block-container types and a registered synthetic player accepted planned replacements/empty slots and preserved item damage components and reservations. Ordinary setters after a trusted call still cancelled coordination.
- Before-image mismatch, oversized replacement, invalid slot, wrong owner, off-thread calls and released leases were refused without changing contents. Detached players were refused. All five randomizable types rejected deferred loot without unpacking it.
- Private ticket probes verified exact slot, stack object, container identity and entry ordering, consumption before side effects and refusal of duplicate entries. An unrelated guarded callback during a ticket cancelled all block coordination rather than borrowing authorization. These probes exercised the actual bridge on server threads; the private agent replaced no Minecraft bytecode.
- Normal restarts without agents passed both loaders, returned expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both dedicated servers are stopped with alpha.32 installed, original configs restored, synthetic player files archived, test blocks/tickets removed and the matching optional Fabric WorldEdit adapter present.
- Schema 8, config 6 and audit formats are unchanged. Isolated checks produced zero container records. Standard jars remain SQLite-only. Private instructions, agents, tooling, logs and databases stay outside Git and source archives. See [validation data](validation/reserved-setter-alpha32.json).

This is an internal single-slot write primitive, not a transaction or save acknowledgement. Setter side effects may precede a failed postcondition; cancellation does not undo writes. Full-owner preconditions, remaining mutation exclusion, connected-client synchronization, journal integration and actual saved-state completion remain pending. Commands never call this primitive or acquire reservations; item rollback apply remains disabled.

## Historical alpha.31 explicit write-scope checkpoint

# Guardian explicit write-scope checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.31+1.21.1`.

- All 273 tests passed: 224 common and 49 Minecraft tests. Eleven new contract tests cover single-owner authorization, foreign registries, expired/released leases, leaked and deferred permits, nested scopes, failure cleanup, replacement identity, server stop and wrong-thread access.
- Clean build, repeated build with configuration-cache reuse and separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0. The final clean build reported no compiler warnings. IDEA reported no problems in the new files.
- Both packaged loader artifacts contain the shared write-scope class. Standard jars remain SQLite-only; schema 8, config 6 and audit formats are unchanged.
- Normal Fabric and NeoForge restarts without test agents passed, returned the expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both dedicated servers are stopped with alpha.31 installed and the matching optional Fabric WorldEdit adapter present.
- Private guidance, agents, tooling, logs and databases remain outside Git and source archives. See [validation data](validation/write-scope-alpha31.json).

The scope authorizes an explicit synchronous callback for one reserved owner. It does not write inventories or grant ambient permission to gameplay hooks. This milestone changes shared core code only; the previous live mutation matrix was not repeated. Audited setter integration, remaining mutation exclusion, live identity verification and actual saved-state completion remain pending. Item rollback apply stays disabled.

## Historical alpha.30 direct-container checkpoint

# Guardian direct-container checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.30+1.21.1`.

- All 262 tests passed: 213 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0. Common reservation expiration/permit tests passed after adding an idle-registry fast path.
- IDEA reported no problems in the changed bridge and five shared mixins. Both loader artifacts package required base/randomizable/hopper/furnace mutation and NBT/component hooks.
- Fabric and NeoForge each passed 82 live container checks with item logging off/on: 328 total across barrel, chest, hopper, dispenser, dropper, furnace, blast furnace and smoker. Ordinary item reads and serialization preserved reservations. Direct setters/removals/clearing revoked all operations and produced the expected stack counts.
- NBT reloads through both entry points restored the expected items. Component application through item-stack and direct map/patch entry points cleared contents as expected. Randomizable containers also revoked reservations when setting loot seeds and clearing loaded loot-table metadata. Tests did not unpack/generate loot.
- The first Fabric run found hopper slot overrides that bypassed the shared setters. Explicit hopper insertion/removal guards repaired the gap; the final complete matrix passed on both loaders. Private agents replaced no Minecraft bytecode and created no world players.
- Normal restarts without agents passed both loaders, returned expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both servers are stopped with alpha.30 installed, original configs restored, temporary blocks/tickets removed and the matching optional Fabric WorldEdit adapter installed.
- Schema 8, config 6 and audit formats are unchanged. Isolated checks produced zero container records. Standard artifacts remain SQLite-only. Private instructions, agents, tooling, logs and databases stay outside Git and archives. See [validation data](validation/direct-containers-alpha30.json).

Detached/off-thread containers, direct mutable stack/list/component writes, arbitrary overrides, bypassing capabilities, loot-producing reads and actual saved-state completion remain pending. Direct changes proceed normally after cancellation; unrelated guarded writes can also cancel coordination. The owner whitelist is unchanged. Commands do not acquire reservations; item rollback apply remains disabled.

## Historical alpha.29 equipment and item-tick checkpoint

# Guardian equipment and item-tick checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.29+1.21.1`.

- All 262 tests passed: 213 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- IDEA reported no problems in the changed bridge, four shared mixins or NeoForge-only durability mixin. Required hooks are packaged in the corresponding loader artifacts.
- Fabric passed 16 live synthetic-player checks per logging setting and NeoForge passed 17: 66 total with logging off/on. Mainhand/offhand/armor replacement and inherited hand setters revoked reservations and retained ordinary contents. Armor/helmet/direct equipment damage applied the expected durability; armor and shield breaks retained ordinary empty-slot behavior.
- Player-attributed durability overloads revoked reservations before damage. Break consumers observed invalidation before callback entry, including NeoForge's extra LivingEntity overload. Equipment processing continued normally. Vanilla shield-break use flags were cleaned up through ordinary stop behavior in the fixture.
- An unrelated reservation paused stack pop-time and contents through both direct ItemStack.inventoryTick and ordinary Inventory.tick. Both paths resumed after release without consuming the stack.
- Normal restarts without agents passed both loaders, returned expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both servers are stopped with alpha.29 installed, original configs restored, temporary blocks/tickets removed and the matching optional Fabric WorldEdit adapter installed.
- Schema 8, config 6 and audit formats are unchanged. Isolated checks produced zero container records. Standard artifacts remain SQLite-only. Private instructions, agents, tooling, logs and databases stay outside Git and archives. See [validation data](validation/equipment-alpha29.json).

Direct mutable stack/list/component writes, unattributed damage, direct item callbacks, arbitrary modded replacements, connected-client acceptance and actual saved-state completion remain pending. These hooks add coordination, not equipment/damage/entity history. Commands do not acquire reservations; item rollback apply remains disabled.

## Historical alpha.28 ongoing-item-use checkpoint

# Guardian ongoing-item-use checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.28+1.21.1`.

- All 262 tests passed: 213 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- IDEA reported no problems in the changed bridge or shared ongoing-use mixin. Both loader configurations package the required wrappers for start, outer continuation, direct tick, completion, release and stop.
- Fabric and NeoForge each passed 12 live synthetic-player checks with item logging off/on: 48 total. Reserved direct start refused use and refreshed inventory state. Existing apple use retained its timer, stack, hunger and active state through outer/direct ticks and completion. After release, progress resumed and ordinary completion consumed one apple, restored four hunger points and ended use.
- Milk completion held its stack while reserved, then returned exactly one empty bucket after release. Active stop revoked all operations before proceeding without food consumption. Charged bow release revoked operations and consumed exactly one arrow. Inactive stop/release preserved reservations and ammunition.
- Normal restarts without agents passed both loaders, returned expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both servers are stopped with alpha.28 installed, original configs restored, temporary blocks/arrows/tickets removed and the matching optional Fabric WorldEdit adapter installed.
- Schema 8, config 6 and audit formats are unchanged. Isolated checks produced zero container records. Standard artifacts remain SQLite-only. Private instructions, agents, tooling, logs and databases stay outside Git and archives. See [validation data](validation/ongoing-use-alpha28.json).

Direct mutable stack/list writes, arbitrary modded replacements, connected-client acceptance and actual saved-state completion remain pending. This adds use coordination, not consumption/projectile logging. Commands do not acquire reservations; item rollback apply remains disabled.

## Historical alpha.27 item-use and inventory checkpoint

# Guardian item-use and inventory checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.27+1.21.1`.

- All 262 tests passed: 213 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- IDEA reported no problems in the changed bridge or two new mixins. Both loader configurations package the required hooks.
- Fabric and NeoForge each passed 21 live checks with item logging off/on: 84 total. Setters, both insertion overloads, removal variants, clearing, copying, loading, dropping, picking and both returned-item overloads revoked affected whole operations while preserving unrelated reservations and ordinary results.
- A pure count-only clear query preserved player/extra-container items and all reservations. A modifying clear revoked all operations before removing those items. Reserved item use refused consumption and refreshed remote inventory state; reserved use-on-block refused placement. After release, a snowball consumed one item and a stone block placed normally.
- The first Fabric run caught an overloaded insertion selector that had remapped only one method. Explicit descriptors for insertion and returned-item overloads resolved the gap. A separate hotbar-picking fixture expectation was corrected against vanilla source. The final matrix passed in full; private agents replaced no Minecraft bytecode.
- Normal restarts without agents passed both loaders, returned expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Both servers are stopped with alpha.27 installed, original configs restored, temporary blocks/drops/projectile/tickets removed and the matching optional Fabric WorldEdit adapter installed.
- Schema 8, config 6 and audit formats are unchanged. Isolated checks produced zero container records. Standard artifacts remain SQLite-only. Private instructions, agents, tooling, logs and databases stay outside Git and archives. See [validation data](validation/inventory-use-alpha27.json).

Ongoing uses, direct mutable stack/list writes, arbitrary modded mutations, connected-client acceptance and actual saved-state completion remain pending. Count-only coverage does not certify predicates with side effects. Commands do not acquire reservations; item rollback apply remains disabled.

## Historical alpha.26 player lifecycle checkpoint

# Guardian player lifecycle checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.26+1.21.1`.

- All 262 tests passed: 213 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- IDEA reported no problems in the changed bridge and two lifecycle mixins. Required wrappers loaded on both platforms. Existing tested common menu cleanup policy is reused.
- Fabric and NeoForge each passed 14 live synthetic-player checks with item logging off/on: 56 total. Inventory copying invalidated both donor/recipient operations while retaining normal contents. Known participating menus invalidated whole operations and preserved unrelated reservations; unknown ownership invalidated all remaining operations.
- Same-dimension and real Nether transitions completed with reservations revoked before their post-transition callbacks. Death processing completed, disconnect saving wrote normal player files, and respawn replaced the player instance with the expected inventory. Unrelated known reservations stayed active throughout these cases.
- An initial NeoForge respawn fixture lacked connection channel metadata used by sendLevelInfo. Adding an in-memory channel to private test tooling allowed the full vanilla/NeoForge path to run. Gameplay code was unchanged by the test repair; no Minecraft bytecode was replaced.
- Normal restarts without test agents passed both loaders, returned the expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Alpha.26 is installed with the matching optional Fabric WorldEdit adapter. Both servers are stopped, original configs restored and fixture blocks, drops, synthetic player files and temporary chunk tickets removed. Synthetic files were verified absent beforehand and archived outside Git afterwards.
- Schema 8, config version 6 and audit formats are unchanged. This milestone adds reservation invalidation, not player/entity capture or verified rollback completion saves. Isolated runs produced zero item records. Standard artifacts remain SQLite-only. Private test sources, agents, logs, databases and guidance stay outside Git and distribution archives. See [validation data](validation/player-lifecycle-alpha26.json).

Remaining inventory mutation paths, connected-client/modpack acceptance and actual saved-state completion remain pending. Commands do not acquire reservations; item rollback apply remains disabled.

## Historical alpha.25 brewing/crafter checkpoint

# Guardian brewing/crafter checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.25+1.21.1`.

- All 262 tests passed: 213 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- IDEA reported no problems in the changed bridge and three new mixins. Required brewing tick, crafter activation and crafter animation tick wrappers loaded on both platforms. The existing tested common automation policy is reused.
- Fabric and NeoForge each passed 12 live checks with item logging off/on: 48 total. Reserved brewing stands held fuel, ingredient, potion and start timer unchanged. After release, fuel consumption and brewing began normally; an unrelated reservation also held a pending brew at completion, then release produced the expected awkward potion and consumed exactly one ingredient.
- Reserved crafter activation left inputs, output destinations, remainder output and animation untouched. Fresh activations after release produced four planks from one log through ejection and barrel insertion. A cake recipe consumed all nine inputs and delivered one cake plus exactly three returned buckets. Reserved crafter animation counters/block states stayed unchanged and completed normally after release.
- Normal restarts without test agents passed both loaders, returned the expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Alpha.25 is installed with the matching optional Fabric WorldEdit adapter. Both servers are stopped, original configs restored, fixture inventories/blocks and temporary chunk tickets removed. Ejected fixture planks were cleaned throughout the test area's vertical extent.
- Schema 8, config version 6 and audit formats are unchanged. This milestone adds coordination, not brewing/crafter history or recipe rollback. Isolated runs produced zero item records. Standard artifacts remain SQLite-only. Private test agents, logs, databases and guidance stay outside Git and distribution archives. See [validation data](validation/brewing-crafter-alpha25.json).

Remaining mutation paths, connected-client/modpack acceptance and verified completion saves remain pending. Commands do not acquire reservations; item rollback apply remains disabled.

## Historical alpha.24 dispenser/dropper checkpoint

# Guardian dispenser/dropper checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.24+1.21.1`.

- All 262 tests passed: 213 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- IDEA reported no problems in the changed common policy, tests, Minecraft bridge and both new mixins. Required activation wrappers loaded on Fabric and NeoForge.
- Both loaders passed dispenser ejection, dropper ejection and dropper-to-barrel insertion checks with item logging off/on: 24 checks total. A reservation on a separate barrel held sources, destinations and item creation unchanged. Fresh redstone activation after release resumed dispensing.
- Four common regression tests cover player/block reservations, multiple simultaneous operations, invalidation, expiry, shutdown and thread confinement. Guards run before random slot selection or item reads and before custom dispense or NeoForge capability callbacks. Any active reservation pauses dispensing; skipped activations are not replayed.
- An initial server start found an occupied port. Tests used an unused port without editing server properties or interrupting another process. An initial NeoForge fixture check was repeated after warming newly generated chunks to ticking status, so queued activations could not be confused with refused activations.
- Normal restarts without test agents passed both loaders, returned the expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Alpha.24 is installed with the matching optional Fabric WorldEdit adapter. Both servers are stopped, original configs restored and temporary fixtures/chunk tickets removed.
- Schema 8, config version 6 and audit formats are unchanged. This milestone adds coordination, not dispenser/dropper audit capture. Isolated runs produced zero item records. Standard artifacts remain SQLite-only. Private test agents, logs, databases and guidance stay outside Git and distribution archives. See [validation data](validation/dispenser-dropper-alpha24.json).

Other ticking inventories, custom mutation paths, connected-client/modpack acceptance and verified completion saves remain pending. Commands do not acquire reservations; item rollback apply remains disabled.

## Historical alpha.23 furnace/lifecycle checkpoint

# Guardian furnace/lifecycle checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.23+1.21.1`.

- All 258 tests passed: 209 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- Five new common tests cover chunk-scoped whole-operation invalidation, negative coordinates, dimensions, stale leases, expiry, shutdown and thread confinement. IDEA reported no problems in the changed coordination code, tests and three lifecycle/tick mixins.
- Fabric and NeoForge each passed 13 lifecycle checks with item logging disabled/enabled: 52 total. Furnace, blast furnace and smoker inputs, fuel, outputs and timers stayed unchanged while reserved and recipes completed after release. An unrelated furnace kept processing.
- Identical block-state writes kept reservations. Block replacement, same-block inventory object replacement, direct removal, the opposite chest-half change across a chunk boundary and the actual server unload callback invalidated reservations. Unload tests verified the complete live/pending block-entity set belonged only to a temporary fixture. These checks produced no item transaction records; smelting audit capture is not added.
- Existing hopper guards passed all 16 source/destination/hopper/opposite-half cases across both loaders with automatic logging disabled/enabled. Final powered/enabled state was established before reservations to separate tick exclusion from intentional structural cancellation. Enabled runs each recorded exactly seven transfers; disabled runs recorded zero.
- Initial test comparisons were corrected for NeoForge's integer furnace timer NBT. The unload harness refused a chunk containing nonfixture block entities, then selected a verified empty chunk. A transient Windows control-file sharing lock received a bounded retry. None of these fixes changed gameplay code or normal databases.
- Normal restarts without test agents passed both loaders, returned the expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Alpha.23 is installed, including the matching optional Fabric WorldEdit adapter. Both servers are stopped, original configs restored and temporary fixtures/chunk tickets removed. Coal item drops in the automated hopper fixture boxes were cleaned up.
- Schema 8, config version 6 and audit formats are unchanged. Standard artifacts remain SQLite-only. Test sources, agents, logs and databases stay outside Git and distribution archives. See [validation data](validation/furnace-lifecycle-alpha23.json).

Other automation, custom ticking implementations, off-thread mod mutations, connected-client/modpack acceptance and verified completion saves remain pending. Commands do not acquire reservations; item rollback apply remains disabled.

## Historical alpha.22 menu/player checkpoint

# Guardian menu/player-reservation checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.22+1.21.1`.

- All 253 tests passed: 204 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- IDEA inspections found no problems in the changed coordination bridge and seven menu/player mixins. Both servers loaded the required mixins successfully.
- Synthetic server players exercised real packet handlers with item transaction logging disabled/enabled. Fabric passed 24 checks per run and NeoForge 25, including its extended opening API: 98 total. Refused click prediction never reached the remote cache; resynchronization sent authoritative slot/cursor contents with an advanced state ID.
- Reserved drops, swaps, creative slot/direct-drop packets and a valid unlocked recipe placement were refused. Clicks, swaps, creative writes and that recipe resumed after release. Physical barrel and combined-container owner checks passed. Known unrelated opening/cleanup remained available; unknown providers were refused before menu creation and unknown close invalidated remaining operations. Closing returned carried items without loss.
- Each enabled run recorded exactly six accepted transactions; disabled runs recorded zero. Rejected actions produced no phantom records. The external agent invoked actual server methods and did not replace Minecraft bytecode. Temporary databases, sources, agents and logs stay outside Git and the distribution.
- Normal restarts without the agent passed both loaders, returned the expected three/two historical transactions and zero unfinished journals, and preserved normal audit/journal tables. Alpha.22 is installed, including the matching optional Fabric WorldEdit adapter. Both servers are stopped; original configs are restored and fixtures/forced chunks are removed.
- Schema 8, config version 6 and audit formats are unchanged. SQLite remains the standard bundled driver. See [validation data](validation/menu-coordination-alpha22.json).

Connected-client visual behavior and modpack/claim combinations still need player acceptance. Ticking inventories, replacement/unload, other unsupported automation and verified completion saves remain pending. Commands do not acquire reservations and item apply remains disabled.

## Historical alpha.21 menu-policy checkpoint

# Guardian menu-policy checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.21+1.21.1`.

- All 253 tests passed: 204 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- Twelve new policy tests cover actor/participant reservations, unrelated menus, bounded and failed resolution, cleanup of whole operations, unknown cleanup, expiry, release, stop and thread confinement.
- IDEA inspections found no problems in the changed coordinator and policy tests.
- No Minecraft hooks changed. These tests establish common policy behavior, not gameplay menu exclusion. Dedicated servers remain stopped on the verified alpha.20 build; no server worlds or databases changed.
- Schema 8, config version 6 and audit formats are unchanged. Standard artifacts remain SQLite-only.

Next: connect packet, player action and menu cleanup hooks on both loaders, verify authoritative inventory resynchronization and actual menu paths, then cover other mutation paths and saved-state completion. Item apply remains disabled.

## Historical alpha.20 hopper-reservation checkpoint

# Guardian hopper-reservation checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.20+1.21.1`.

- All 241 tests passed: 192 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- Nine new transfer-gate tests cover idle behavior, each participating owner, unrelated dimensions, failed/unsupported resolution, duplicate physical endpoints, expiry, release/invalidation, stop and thread confinement.
- IDEA reported no problems in the changed hopper mixin or its Minecraft coordination bridge.
- Fabric and NeoForge each passed four active reservation cases with logging off and on: source, destination, hopper and the opposite half of a double chest across a chunk boundary. Items remained unchanged while reserved; transfers resumed after release. Enabled runs each recorded seven successful transfers and disabled runs recorded none.
- The external test agent only scheduled acquisition/release on the server thread. It did not replace hopper code. Initial fixture failures were corrected with real redstone power and asserted double-chest states before acceptance. Test sources, agents, logs, databases and backups stay outside Git and the distribution.
- Normal restarts without the test agent passed both loaders, returned the expected three/two historical transactions and no unfinished journals, and preserved all normal audit/journal tables. Alpha.20 is installed; both servers are stopped with original configs restored and fixtures/forced chunks removed.
- Schema 8, config version 6 and audit formats are unchanged. SQLite remains the standard bundled driver. See [validation data](validation/hopper-coordination-alpha20.json).

Hopper coordination is one part of exclusive inventory access. Menus, player actions, ticking inventories, replacement/unload, unsupported modded capabilities, verified save completion and item apply remain pending.

## Historical alpha.19 owner-reservation checkpoint

# Guardian owner-reservation checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.19+1.21.1`.

- All 232 tests passed: 183 common and 49 Minecraft tests. Clean build, repeated build with configuration-cache reuse and the separate WorldEdit adapter build passed through IDEA with Java 21 and Gradle 9.8.0.
- Fifteen new tests cover atomic acquisition, overlap/duplicate refusal, bounds, immutable owner sets, exact scope, whole-operation invalidation, stale/foreign permits, timeout, monotonic clock wraparound, shutdown and thread confinement.
- The save-completion interruption test verifies that an invalidated reservation stops further saves and journal completion. Late readback and stale close cannot affect a replacement reservation with the same operation UUID.
- IDEA inspections reported no problems in the coordinator and its tests. MixinMCP checked vanilla and NeoForge hopper paths and the NeoForge furnace tick to identify mutation paths that menu locking alone cannot cover.
- No gameplay hooks or inventory setters were added. No server runtime acceptance is claimed for owner exclusion. Both dedicated servers remain stopped on the previously verified alpha.18 build; no databases or worlds were changed for this milestone.
- Schema 8, config version 6 and audit formats are unchanged. Standard artifacts remain SQLite-only. See [validation data](validation/owner-coordination-alpha19.json).

The reservation registry is common coordination infrastructure. Live platform exclusion, identity/content checks, verified saves and item apply remain pending.

## Historical alpha.18 saved-player checkpoint

# Guardian saved-player reader checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.18+1.21.1`.

- All 217 tests passed: 168 common and 49 Minecraft tests. Clean build, configuration-cache reuse and separate WorldEdit adapter builds passed with Java 21 and Gradle 9.8.0. The known Loom SQLite metadata warning remains.
- Seven decoder tests cover saved main/armor/offhand mapping, unsigned slot 150, counts/components, unchanged input NBT, UUID/version checks, inventory/slot validation and selected-owner scope.
- Five reader tests cover gzip file reads, no backup fallback, encoded/decoded budgets, queue capacity, background execution, reentrant completion and stopped/late reads. The shutdown test caught and verified a publication-order race fix.
- Fabric and NeoForge used isolated random-UUID offline player files and a seeded coherent main/offhand journal. Saved comparisons returned ORIGINAL, RESTORED and UNAVAILABLE for UUID mismatch, corruption and missing files. Commands preserved file hashes and journal/claim rows. These are real file-reader/command tests with synthetic player data, not connected-player save/concurrency acceptance.
- Both loaders retained saved block comparisons and normal history hashes/queries across restart. Alpha.18 SQLite-only jars are installed; both servers are stopped with original configurations restored and test files/rigs removed. Databases, logs and backups stay outside Git.
- Schema 8, config version 6 and audit formats are unchanged. See [validation data](validation/saved-player-alpha18.json).

Exclusive gameplay coordination, connected-player save/readback concurrency, unknown modded saved inventory layouts, coordinated journal completion and mutating item apply remain pending. Readback does not force a save or establish power-loss durability.

## Historical alpha.17 saved-container checkpoint

# Guardian saved-container reader checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.17+1.21.1`.

- All 205 tests passed: 168 common and 37 Minecraft tests. Clean build, configuration-cache reuse and separate WorldEdit adapter builds passed with Java 21 and Gradle 9.8.0. The known Loom SQLite metadata warning remains.
- Eight new saved-NBT tests cover exact counts/components, empty slots, unchanged input NBT, malformed item lists/slots, duplicate/missing owners, versions/coordinates/status, unknown layouts/items, physical slot bounds and sealed loot.
- Fabric and NeoForge actual region reads returned ORIGINAL from saved data while live slots were RESTORED with saving disabled. After explicit server save/flush, saved comparison returned RESTORED. Sealed saved loot returned UNAVAILABLE. The real I/O-worker and region accessor mixins ran on both loaders.
- Saved/live comparison commands preserved live items and journal/source/owner claims. Normal audit hashes and history queries passed after restart. Alpha.17 SQLite-only jars are installed; both servers are stopped with original configurations restored and isolated rigs removed. Backups and logs stay outside Git.
- Schema 8, config version 6 and item formats are unchanged. See [validation data](validation/saved-reader-alpha17.json).

This establishes saved block-slot reading, not coordinated save completion or Minecraft crash recovery. Unloaded/missing-region acceptance, saved online-player inventories, unknown modded container layouts, exclusive gameplay coordination and mutating item apply remain pending.

## Historical alpha.16 saved-state protocol checkpoint

# Guardian saved-state protocol checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.16+1.21.1`.

- All 197 tests passed: 168 common and 29 Minecraft tests. Clean build, configuration-cache reuse and the separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0. The known Loom SQLite metadata warning remains.
- Twelve controlled-port tests verify all-owner ordering, exact readbacks, partial failure, ownership loss, journal changes, acknowledgement failure, timeout/stop, phase bounds, recovery-phase completion and callback/thread confinement.
- Two real SQLite tests verify that partial save failure preserves owner/source claims across restart as RECOVERY_REQUIRED, and successful modeled readbacks release owner claims while keeping completed source claims. The port is synthetic; these do not establish Minecraft disk or process-crash acceptance.
- Fabric and NeoForge preview/recovery fixtures passed with their existing observation outcomes. Normal history hashes and queries passed after restart. Alpha.16 SQLite-only jars are installed; both servers are stopped with original settings restored and isolated rigs removed. Logs and databases stay outside Git.
- Schema 8, config version 6 and payload formats are unchanged. See [validation data](validation/save-protocol-alpha16.json).

No live save port is implemented and no command uses the new protocol. Exclusive gameplay coordination, verified Minecraft file flush/readback, process-crash acceptance and item rollback apply remain pending. Read-only observation commands retain their previous scope.

## Historical alpha.15 observation checkpoint

# Guardian observation invalidation checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.15+1.21.1`.

- All 183 tests passed: 154 common and 29 Minecraft tests. Clean build, configuration-cache reuse and separate WorldEdit adapter builds passed with Java 21 and Gradle 9.8.0. The known Loom SQLite metadata warning remains.
- Nine new owner-watch tests cover matching/unrelated owners, dimensions, whole-player temporary-slot ownership, queue rejection, change-and-return activity, detached results, capacity/copy bounds, idempotent release, stopped writers and concurrent submitters.
- Controlled pipeline tests prove invalidation before queue acceptance and under backpressure. Dedicated Fabric and NeoForge fixtures verify preview/recovery wiring and unchanged ORIGINAL, RESTORED, PARTIAL, CONFLICT and UNAVAILABLE results while otherwise idle. Concurrent gameplay during a live check remains a separate acceptance test.
- Normal block/item row hashes and existing history queries passed after restart. Alpha.15 SQLite-only jars are installed; both servers are stopped with original settings restored and isolated rigs removed. Logs, databases and backups stay outside Git.
- Schema 8, config version 6 and payload formats are unchanged. See [validation data](validation/owner-observation-alpha15.json).

These watches observe captured audit attempts; they do not freeze gameplay or cover unsupported/unsubmitted changes. Exclusive mutation coordination, durable world/player saves and item rollback apply remain pending.

## Historical alpha.14 recovery view checkpoint

# Guardian item recovery view checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.14+1.21.1`.

- All 174 tests passed: 145 common and 29 Minecraft tests. Clean build, configuration-cache reuse and the separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0. The known Loom SQLite version metadata warning remains.
- Nine new recovery tests cover exact components, all observation outcomes, incomplete/unavailable observations, owner identity invalidation, immutable owner lists, incoherent/duplicate chains, transient owners, conservation and payload/owner/entry bounds.
- Fabric and NeoForge isolated fixtures each previewed a complete two-transfer hopper chain. A seeded RECOVERY_REQUIRED journal reported ORIGINAL, RESTORED, PARTIAL, CONFLICT and UNAVAILABLE for controlled live inventories. Every command preserved sampled contents, journal rows, source claims and owner claims. BOTH is covered by a cyclic pure-domain fixture.
- Normal block/item row hashes and existing three-record/two-record history queries passed after restart on both loaders. Normal journals list zero unfinished operations. Alpha.14 SQLite-only jars are installed; both servers are stopped with original settings restored and test rigs removed. Databases, logs and backups stay outside Git.
- Schema 8, config version 6 and payload formats are unchanged. See [validation data](validation/item-recovery-alpha14.json).

Recovery remains read-only. Multi-tick observations and live identity checks do not freeze gameplay or certify saved inventories. Connected-client UUID completion, online-player recovery observations, identity replacement during a live check, gameplay coordination and durable save/crash reconciliation remain pending. Item rollback apply is disabled.

## Historical alpha.13 audit barrier checkpoint

# Guardian audit barrier checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.13+1.21.1`.

- All 165 tests passed: 136 common and 29 Minecraft tests. Clean build, configuration-cache reuse build and separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0. The known Loom SQLite version metadata warning remains.
- Eleven new barrier tests cover delayed commit, delayed flush, append retry, flush failure/retry, empty prefixes, bounded requests, cancellation, unforgeable internal receipts, reentrant submission callbacks, concurrent submitters and startup/shutdown failure handling.
- Controlled backends verify that an accepted prefix is not acknowledged before its append and flush, and that later submissions need not finish for an earlier prefix. These are pipeline fault tests, not Minecraft save/recovery tests.
- Fabric and NeoForge live previews now use the barrier before history lookup. Both loaders still returned two eligible records for a complete hopper chain, rejected a filtered-out newer transfer, and rejected a block-history witness with its dependent older record. Inventory contents stayed unchanged.
- Existing block/item row hashes and three-record/two-record normal history queries passed after restart on both loaders. Final SQLite-only alpha.13 jars are installed; both servers are stopped with original configurations restored and fixtures removed. Logs, test databases and backups are outside Git; production files were untouched.
- Schema 8, config version 6 and GCT1/GCT2 formats are unchanged. See [validation data](validation/audit-barrier-alpha13.json) for artifact hashes and limits.

The receipt covers the accepted audit prefix, not rejected captures, pending work outside this pipeline or future gameplay. Preview remains read-only and can become stale. Live inventory coordination, fresh mutation-time checks and durable player/chunk save reconciliation remain open; item rollback apply is disabled. See [item rollback](ITEM_ROLLBACK.md).

## Historical alpha.12 history guard checkpoint

# Guardian item rollback history guard checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.12+1.21.1`.

- All 154 tests passed: 125 common and 29 Minecraft tests. Clean build, configuration-cache reuse build and separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0. The known Loom warning about SQLite's four-part JDBC version remains.
- Ten new guard test methods run on SQLite and the optional DuckDB backend. They cover excluded newer/equal-time transactions, independent/older history, block history including rolled-back events, separate dimensions, active/completed journal claims, cursor/grid player ownership, deduplicated UUID retries and multi-page migration. A corrupt payload aborts migration atomically.
- Dedicated Fabric and NeoForge fixtures each recorded a two-transfer hopper chain. The full chain preview returned two eligible records. A source-only query hid the later push and returned one skipped record for excluded newer history. Inventory contents stayed unchanged.
- A synthetic persisted block-change witness at the destination rejected the newest transaction and its dependent older record on both loaders, while inventories stayed unchanged. This tests the history guard, not an unlogged block replacement or live mutation executor.
- Normal server migration backfilled 64,872 owner rows on Fabric and 64,860 on NeoForge. Independent block/item row hashes were unchanged and existing three-record/two-record hopper queries passed after restart.
- Both dedicated servers have the final SQLite-only alpha.12 jars installed and are stopped, with original configurations restored and fixtures removed. Test databases, logs and pre-upgrade snapshots are archived outside Git. Production files were untouched.
- Schema 8 adds the logical-owner index; config version 6 and GCT1/GCT2 payloads are unchanged. See [validation data](validation/item-history-guard-alpha12.json) for artifact hashes and measured fixture counts.

This is a persisted-history safety milestone. Preview observations can still become stale and pending audit writes need a barrier before any apply. Live container identity/ownership, coordinated player/chunk saves and actual Minecraft interruption recovery remain open; item rollback apply is disabled. See [item rollback](ITEM_ROLLBACK.md). Previously pending client/modpack checks also remain open.

## Historical alpha.11 journal checkpoint

# Guardian item rollback journal checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.11+1.21.1`.

- All 144 tests passed: 115 common and 29 Minecraft tests. Clean build, configuration-cache reuse build and separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0. The known Loom warning about SQLite's four-part JDBC version remains.
- Nine journal test methods run on SQLite and the optional DuckDB backend. Coverage includes immutable persisted plans, idempotent retry, source verification, atomic owner/source claims, prepared cancellation, retained completed claims, reverse chains, component mismatches, uncertain cycles and schema-6 migration/older-reader rejection.
- A child process commits APPLYING and exits abruptly without closing JDBC. Reopening each backend marks the operation RECOVERY_REQUIRED, retains its claims and preserves the audit payload. This tests database interruption, not a Minecraft inventory mutation or world-save crash.
- Isolated live fixtures on Fabric and NeoForge migrated to schema 7, detected an injected interrupted operation, reported one unfinished journal and retained three inventory reservations and two source claims. Audit rows remained unchanged; there was no automatic item replay.
- Both normal server databases upgraded to schema 7. Existing three-record and two-record hopper queries passed, and independent hashes of all block/item rows matched before and after startup. Normal databases have no unfinished item journals.
- Final SQLite-only alpha.11 jars are installed on both dedicated servers, which are stopped with original configurations restored. Temporary databases and logs are archived outside Git. No production database or server files were changed.
- Schema 7 adds journal tables and indexes. Config version 6 and GCT1/GCT2 item payload formats remain unchanged. See [validation data](validation/item-journal-alpha11.json) for budgets and artifact hashes.

Item rollback apply remains disabled. The journal does not freeze normal gameplay, coordinate player/chunk save durability or prove that item writes occurred. Fresh history/identity checks, save reconciliation and actual Minecraft interruption acceptance precede enabling writes. See [item rollback](ITEM_ROLLBACK.md). Previously pending crafting client/modpack checks remain open.

## Historical alpha.10 item preview checkpoint

# Guardian item rollback preview checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.10+1.21.1`.

- All 135 tests passed: 106 common and 29 Minecraft tests. Clean build, repeated configuration-cache reuse build and separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0. Loom still reports its known four-part SQLite JDBC version metadata warning; there are no compiler or test failures.
- Dedicated Fabric and NeoForge fixtures each produced 56 unique hopper records in an isolated test database. A two-record transfer chain preview returned two eligible transactions without changing inventory contents.
- Both loaders skipped an endpoint outside the selected region, rejected a changed destination and its dependent older transaction, preserved sealed loot, and refused a selection above the 50-record cap. Before/after inventory reads matched in the read-only cases.
- Restarting with the original database retrieved existing three-record and two-record hopper histories. Independent hashes of all stored block and item rows were unchanged.
- Final SQLite-only alpha.10 jars are installed on both dedicated servers. They are stopped, with original configurations restored and fixtures removed. Test databases, logs and prior jars are archived outside Git. Production files were untouched.
- Schema 6, config version 6 and snapshot formats are unchanged. See [the validation data](validation/item-preview-alpha10.json) for limits and artifact hashes.

This milestone is preview-only. It observes inventories over several ticks and does not reserve them. Item rollback apply, durable journaling, interruption recovery and player-driven recovery acceptance remain pending. See [item rollback](ITEM_ROLLBACK.md). Existing crafting client/modpack acceptance checks also remain open.

## Historical alpha.9 crafting checkpoint

# Guardian crafting correlation checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.9+1.21.1`.

- 118 tests passed: 89 common and 29 Minecraft tests. The clean build, subsequent configuration-cache reuse build and separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0.
- Live synthetic-player probes on Fabric and NeoForge invoked the accepted click packet handler and produced eight unique records each: five CRAFT, one RECIPE_PLACE and two CLOSE. Both reported zero capture failures and backpressure.
- Independent decoding verified consumed ingredients, actual player/cursor gains, twelve-plank bulk output, cake's three empty-bucket leftovers, conservation in noncraft actions and three records indexed at the real crafting-table location.
- Regression coverage includes immutable custom components, distinct player/menu grid ownership, preview exclusion, exact-layout rejection, no-op/canceled correlation, schema-5 migration, older-reader rejection, persistence/restart on both JDBC backends and concise crafting presentation.
- Schema 6 adds guarded crafting ownership and action support. GCT2 encodes grid changes; existing GCT1 records remain readable. Block payloads and config version 6 are unchanged.
- Both dedicated servers have the final SQLite-only alpha.9 jars installed and are stopped, with original configurations restored. Temporary harness mods/databases were removed or archived outside Git, and the table fixture was removed. Production files and the supplied production database were untouched.

Actual-client tests remain for recipe clicks, full inventories, protection cancellation, modded recipes and prediction on both loaders. Unknown crafting layouts and thrown result outputs remain outside this bounded slice. Container rollback is next; temporary grids and recipe transformations require separate recovery rules. See [the crafting test guide](STEP_4_TESTING.md#crafting-acceptance-on-each-loader).

## Historical alpha.8 SQLite checkpoint

# Guardian SQLite distribution checkpoint

Date: 2026-10-07. Checkpoint: `0.4.0-alpha.8+1.21.1`.

- Standard Fabric and NeoForge jars bundle SQLite only and are approximately 11.85 MiB each, down from approximately 88 MiB. Release validation rejects extra bundled drivers or a core jar above 16 MiB.
- All 104 tests pass: 82 common and 22 Minecraft tests. Two new regressions verify missing-driver failure before directory creation and preservation of an existing database.
- Both default builds started on the dedicated servers and retrieved their existing item history. No DuckDB classes loaded. One diagnostic startup per loader measured 10.523 seconds for Fabric and 11.591 seconds for NeoForge; these are observations, not benchmark guarantees.
- Both optional `-PbundleDuckDb=true` builds started with DuckDB selected. Standard builds with DuckDB selected produced the explicit missing-driver error and left existing database files unchanged. No automatic database conversion or fallback occurs.
- The clean build, configuration-cache reuse build and separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0.
- Both dedicated servers retain the default alpha.8 SQLite jars, original SQLite configurations and disabled automated hopper logging, and are stopped. Backups and optional artifacts remain outside Git. Production files were not changed.

Schema 5, config version 6 and snapshot formats remain unchanged. Logging continues through the bounded background batch writer. See [distribution options](DISTRIBUTION_SIZE.md) and [historical startup measurements](STARTUP_VALIDATION.md).

Sealed-source and sealed-destination hopper fixtures also passed on both loaders during this milestone: 12 unique balanced records each, expected conservative first-transfer skips and correct final counts. Next is bounded crafting correlation, followed by conservative container rollback. Connected-client NeoForge protection checks and longer component-heavy load runs remain open; entity and fluid logging remain outside Step 4.

## Historical alpha.7 hopper reliability checkpoint

# Guardian hopper reliability checkpoint

Date: 2026-10-07. Checkpoint: `0.4.0-alpha.7+1.21.1`.

- 102 tests passed: 80 common and 22 Minecraft tests. The clean build passed, the second build reused its configuration cache, and the separate WorldEdit adapter build passed with Gradle 9.8.0 and Java 21.
- Regression tests cover unchanged-container presentation, actual changes in another physical chest half, explicit hopper endpoints, default-payload reuse with independent counts, patched components and transient-component rejection.
- Controlled logging-off/on runs exercised 10, 50 and 100 hoppers on Fabric and NeoForge. No capture/write failures or backpressure occurred. Independent decoding after shutdown confirmed every accepted record persisted, with exact item/component conservation and unique transaction IDs.
- Separate live fixtures passed furnace input/fuel/output slot checks, rejected side fuel insertion and a loaded chunk-boundary transfer. Each loader produced the expected 27 balanced records.
- The user reports that inspector paging, claim-denied inspection and permission revocation work on the supplied pack server. Screenshots confirmed the permission-removal message and the full-barrel rig recorded the pull into the hopper without a barrel insertion. These are user acceptance results; they do not establish the equivalent connected-client behavior on NeoForge.
- The single-barrel “other half” message was a formatting error for inventory-only actions associated with an open menu. The formatter now identifies the affected inventory and states that the inspected container was unchanged.
- Both dedicated servers have alpha.7 installed, are stopped, and have automated hopper logging restored to false. Prior databases, configuration and jars are backed up outside Git. No production server files or supplied production database were changed.

See [the hopper load report](HOPPER_PRESSURE_TEST.md) for sample ranges, fixture details and limits. The optimization is scoped to one inventory read and unmodified component patches; no mutable item stack enters stored history and no long-lived cache was introduced. Schema 5, config version 6 and item payload formats are unchanged.

Remaining staged work includes crafting correlation and conservative container rollback. Sealed-loot runtime acceptance, unloaded-neighbor cases, component-heavy sustained load, longer memory observation and connected-client NeoForge protection/prediction tests remain open. Fluids and entity logging remain outside Step 4.

## Historical alpha.6 validation

# Guardian history presentation and inspector validation

Date: 2026-10-07. Checkpoint: `0.4.0-alpha.6+1.21.1`.

- 97 tests passed: 80 common and 17 Minecraft tests. Clean build passed; the subsequent standard build reused its configuration cache. Java 21, Gradle 9.8.0 and both loader mappings remain unchanged.
- Regression checks cover filter-preserving clickable commands, order validation, chronological block/item pagination on SQLite and DuckDB, navigation click payloads and availability, and visible hopper endpoints.
- Read the supplied production database in immutable read-only mode. The furnace transaction removes one item from source chest 1710,86,3872 and inserts it into hopper 1710,85,3872. No furnace insertion into destination barrel 1710,84,3872 exists in the supplied copy. Block placement history independently identifies these positions. The database was not changed or copied into Git.
- Backed up stopped dedicated-server databases, configs and previous jars outside Git; installed alpha.6 on both fixtures.
- Fabric started with the pack's exact Open Parties and Claims 0.32.7 and Forge Config API Port 21.1.6 jars. Exported transformed packet-listener bytecode shows Guardian's read-only use handler before vanilla scheduling/gameplay callbacks. This confirms hook insertion, not a connected-player claims test.
- Both loaders' console lookups verified source/destination route text, oldest/newest filters, pages and compact navigation. Both servers stopped normally. Removed the two temporary Fabric compatibility jars; hopper logging remains disabled on both fixtures.

Connected-player acceptance is still required for claim-denied inspection, permission revocation, both hands, predicted block/item corrections, actual clickable controls, and the pack's client hooks. NeoForge startup does not prove a live packet action. Existing hopper load, sided furnace, chunk-boundary, crafting-correlation and rollback work remains staged after this presentation milestone. No full-pack, load-benchmark or container-rollback completion claim is made.

## Historical alpha.5 validation

# Guardian command and inspector validation

Date: 2026-10-07. Current checkpoint: `0.4.0-alpha.5+1.21.1`.

- All 93 tests passed: 77 common tests and 16 Minecraft tests. Clean build passed, a subsequent normal build reused its configuration cache, and the optional WorldEdit adapter built successfully with Gradle 9.8.0 / Java 21.
- New regression checks cover alias/player/time completion, pagination parsing, SQLite/DuckDB time/radius/selection filters, paged block/item reads, renamed-player UUID queries, registered modded block IDs, readable net container counts, hopper labels and inspected-container scope.
- Both dedicated servers started with the final interaction and exception-safe placement mixins. Console commands verified coordinates, user/time filters, radius, pages, Guardian usage text and readable system history. Both stopped normally.
- Fabric was additionally tested with the exact Lithium 0.15.4 jar from the supplied pack. A temporary chest/hopper/barrel rig transferred three coal, with six unique persisted system transactions and zero capture/write/backpressure failures. Page 2 returned the older matching transfer. Independent database inspection confirmed all six actor/action records.
- Removed fixture contents/blocks and its temporary force-load; removed the temporary Lithium jar and restored the Fabric configuration. Both hopper switches remain false; both servers are stopped with alpha.5 installed. Prior jars/databases/configuration are backed up outside Git.
- Reviewed the supplied client and server logs; the server loaded Guardian alpha.4. Dusty Decorations placement exceptions are documented in MODPACK_COMPATIBILITY.md. No Guardian capture/write errors were found in that log. The exact missing-block case and actual audit database remain unverified.

Connected-player acceptance remains necessary for both inspector buttons, autocomplete in the client, door/switch actions, and claims/protection cancellation. Server startup and console checks do not prove these packet paths. The full 395-entry server pack was not installed into the dedicated fixtures. The small Lithium stream is not a load benchmark or comprehensive chunk-boundary test. Simply opening a menu is not currently recorded as an item transaction; missing past interactions cannot be reconstructed.

## Historical hopper checkpoint

# Guardian Step 4 hopper validation

Date: 2026-10-07. Current checkpoint: `0.4.0-alpha.4+1.21.1`.

## Automated checks

- Clean build passed with Java 21, Gradle 9.8.0, Fabric Loom 1.17.21, and ModDevGradle 2.0.148.
- All 85 tests passed: 71 platform-neutral tests and 14 Minecraft snapshot/codec tests.
- Standard build reused its configuration cache; the separate WorldEdit adapter build passed.
- New tests cover balanced hopper transfers, no-op/failed attempts, reversed or one-sided transfers, changed components, overlapping owners, topology changes, defensive owner copies, and double-chest context.
- SQLite and DuckDB tests preserve system attribution, deduplicate retries, exclude system history from player queries, upgrade schema 4, and reject the new schema through older migrators.
- Runtime/source jar contents, release workflow validation, source ZIP integrity, and SHA-256 were checked.

## Dedicated server transfer acceptance

- Backed up stopped test databases and configuration outside Git before installing the checkpoint.
- Temporarily enabled `logging.automatedContainerTransfers` on the test servers. Configurations use version 6; storage uses schema 5.
- Created a temporary rig in an inspected empty area: double source chest, hopper, double destination chest, plus a separate hopper facing a full incompatible destination.
- Ran actual transfers on Fabric/SQLite, NeoForge/SQLite, and NeoForge/DuckDB. Each run produced 41 unique transactions: 19 successful pulls and 19 pushes through the double chests, plus three successful pulls into the blocked hopper.
- Failed pushes and empty attempts produced no history. Status reported zero audit/write failures and zero backpressure, with all accepted entries persisted.
- Independently decoded persisted payloads after shutdown on each backend. Every transaction conserved one diamond between two physical owners, system attribution was correct, both double-chest halves were indexed, and persistent custom names survived.
- The final guarded Fabric jar also restarted with hopper logging disabled; the temporary rig still transferred items without new audit submissions.
- Removed the temporary blocks and their contents, removed only the force-load added for the fixture, shut servers down normally, and restored NeoForge to SQLite and both automation switches to false.

## Acceptance boundary

This establishes the tested chest/hopper paths, including NeoForge's capability shortcut, and persistence on both backends. The short single-hopper stream is not a large-server pressure benchmark. Furnaces, other sided/modded containers, protection plugins, sealed-loot behavior, and chunk-boundary cases still need dedicated runtime acceptance.

Player clicks, close/drop/swap packets, creative requests, and protection-mod cancellation still need connected-player acceptance on both loaders. Crafting, other automation mechanisms, loose item pickup, entity inventories, unrelated capability storage, disconnect cleanup, and container rollback remain outside this checkpoint. Fluids and entity logging remain outside Step 4. See [the acceptance guide](STEP_4_TESTING.md).

## Historical inventory and creative checkpoint

# Guardian Step 4 inventory and creative validation

Date: 2026-10-07. Current checkpoint: `0.4.0-alpha.3+1.21.1`.

## Automated checks

- Clean build passed with Java 21, Gradle 9.8.0, Fabric Loom 1.17.21, and ModDevGradle 2.0.148.
- All 76 tests passed: 62 platform-neutral tests and 14 Minecraft snapshot/codec tests.
- The standard build reused its configuration cache; the separate WorldEdit adapter build passed.
- New Minecraft tests capture inventory, armor, offhand, and cursor through real inventory-menu classes, check detached item snapshots, reject crafting and invalid indices, and reject extra/replaced menu slots.
- Correlation tests record inventory-to-cursor moves and component-only creative replacements, while unchanged creative replacements produce no transaction.
- SQLite and DuckDB tests persist CREATIVE_SET, query it by name/UUID, upgrade schema 3, and refuse schema 4 through an older migrator. Existing block and container tests remain passing.
- Runtime/source jar contents, release workflow validation, source ZIP integrity, and SHA-256 were checked.

## Dedicated server smoke tests

- Backed up stopped test databases and configuration outside Git before installing the new jars.
- Fabric core plus WorldEdit 7.3.8 and its optional adapter reached `Done` with SQLite schema 4.
- NeoForge reached `Done` with SQLite schema 4 and DuckDB schema 4.
- Status showed a running writer and zero audit/write failures. Player and position lookups completed without errors and returned empty history.
- Servers shut down normally; NeoForge's backend setting was restored to SQLite after DuckDB testing.
- The initial NeoForge saved-world mod-version notice disappeared on the next start. Its standard resource URL notices remain; no Guardian initialization errors appeared.

## Acceptance boundary

No player connected during these checks. Tests exercise snapshots, eligibility, correlation, storage, and migration; idle-server startup does not establish packet-driven gameplay acceptance. Inventory-screen clicks, armor/offhand movement, cursor returns, accepted/rejected creative requests, and protection-mod interactions still require acceptance on both loaders. See [the acceptance guide](STEP_4_TESTING.md).

Inventory-screen capture requires the standard menu layout and an empty crafting area before and after the action. Crafting/result clicks, direct creative drops, automated transfers, unsupported menus, disconnect cleanup, and container rollback remain outside this checkpoint. Fluids and entity logging remain outside Step 4.

## Historical close and standalone action checkpoint

# Guardian Step 4 close and player item validation

Date: 2026-10-07. Current checkpoint: `0.4.0-alpha.2+1.21.1`.

## Automated checks

- Clean build passed with Java 21, Gradle 9.8.0, Fabric Loom 1.17.21, and ModDevGradle 2.0.148.
- All 69 tests passed: 61 platform-neutral tests and 8 Minecraft codec tests.
- The standard build reused its configuration cache. The separate WorldEdit adapter build passed.
- New tests cover close-time cursor return, standalone drops and offhand correlation, no-op actions, ownership validation, nested capture suppression, exception cleanup, and thread ownership.
- SQLite and DuckDB tests persist all new action kinds, query player names without case sensitivity, reopen history by UUID, upgrade schema 2, and reject schema 3 through an older migrator.
- Existing component, block history, atomic-batch, retry, and migration tests remain passing.
- Runtime/source jar contents, release workflow validation, source ZIP integrity, and SHA-256 were checked.

## Dedicated server smoke tests

- Backed up stopped schema-2 test databases outside Git before installing the checkpoint.
- Fabric core plus WorldEdit 7.3.8 and the optional adapter reached `Done` with SQLite schema 3.
- NeoForge reached `Done` with SQLite schema 3, DuckDB schema 3, and a subsequent SQLite restart.
- Status reported a running writer with zero audit/write failures.
- Player-name and block-position lookups returned empty history on both loaders. UUID lookup also passed on NeoForge.
- NeoForge reported the expected saved-world mod-version change on the first upgrade; it disappeared on restart. Its standard resource URL notices remain. No Guardian initialization or mixin errors appeared.
- Servers shut down normally. NeoForge's backend setting was restored to SQLite.

## Acceptance boundary

No player connected during these checks. Startup verifies transformed server classes and database access, but does not establish gameplay acceptance of clicks, close-time cursor returns, Q/Ctrl-Q drops, offhand swaps, or protection-mod cancellation. Follow [the acceptance guide](STEP_4_TESTING.md) on both loaders before release.

Other player inventory-menu/creative packets, automated transfers, unsupported menus, disconnect cleanup, and container rollback remain outside this checkpoint. Fluids and entity logging remain outside Step 4.

## Historical first Step 4 checkpoint

# Guardian Step 4 validation

Date: 2026-10-07. Current checkpoint: `0.4.0-alpha.1+1.21.1`.

## Automated checks

- Clean build passed on Java 21, Gradle 9.8.0, Loom 1.17.21, and ModDevGradle 2.0.148.
- All 58 tests passed: 50 platform-neutral tests and 8 Minecraft codec tests.
- Both loader artifacts compile the same Mojang-mapped item snapshotter and container hook.
- The item codec tests cover name, damage, explicit default-component removal, count-independent payloads, custom-data ordering, empty stacks, corrupt payloads, identity mismatch, and transient-component rejection.
- Correlation tests cover immutability, cancellation, no-op actions, topology changes, component-only changes, and one ID across multiple slot changes.
- SQLite and DuckDB tests cover complete transaction round trips, restart, retry deduplication, location/actor filtering, cursor-only context, atomic rollback of a mixed block/container batch, successful retry, and schema-1 upgrade with an existing block row.
- Repeated standard build reused the configuration cache. Separate WorldEdit adapter build passed.
- Runtime jars, source jars, workflow release validation, ZIP integrity, and SHA-256 were checked.

## Dedicated server smoke tests

- The stopped test databases were backed up outside the repository before migration.
- Fabric core plus the optional WorldEdit adapter reached `Done` using SQLite schema 2.
- NeoForge reached `Done` using SQLite schema 2 and DuckDB schema 2.
- `/guardian status` reported a running writer and zero capture/write failures.
- `/guardian transactions 0 64 0` completed its asynchronous lookup on both loaders and returned an empty history, as expected without connected players.
- The new Fzzy logging setting defaulted to true and was written into configuration version 5. The first upgrade emitted Fzzy's missing-new-field notice; later startup read the updated configuration normally.
- Servers were shut down normally, and SQLite configuration was restored after DuckDB testing.

## Acceptance boundary

No player was connected during these smoke tests. Actual packet-driven clicks, protection-mod cancellation, double-chest addressing, offhand swaps, drag distribution, creative cloning, and modded persistent components still need player acceptance on both loaders. Automated storage and codec tests do not establish those gameplay paths.

This is the first Step 4 slice, not a declaration that all item paths are complete. Close-time cursor returns, standalone inventory/drop packets, automated transfers, crafting/trading/ender-chest/entity inventories, and container rollback remain outside this checkpoint. See [the acceptance guide](STEP_4_TESTING.md).

## Historical Step 3 checkpoint

# Guardian validation

Date: 2026-10-07. Current checkpoint: `0.3.0-alpha.5+1.21.1`.

## Build results

- Java 21.0.10, Gradle wrapper 9.8.0, Fabric Loom 1.17.21, ModDevGradle 2.0.148.
- Fabric, the optional Fabric WorldEdit adapter, and NeoForge all compile using official Mojang mappings.
- `clean build --no-daemon --warning-mode all` passed all 38 tests: 35 platform-neutral tests and 3 Minecraft NBT codec tests.
- The first plain `build` stored its task graph; repeating the identical command reused the configuration cache and passed.
- Separate `:worldedit-adapter:build --no-daemon --warning-mode all` passed.
- Both loader source jars contain the shared Minecraft and domain sources.
- Workflow YAML passed duplicate-key validation. The embedded release script ran locally and checked Fabric core, NeoForge core, and Fabric WorldEdit runtime artifacts, including NeoForge JDBC jar-in-jar metadata.
- The source archive excludes Git internals, build caches, server worlds, logs, databases, and credentials. ZIP integrity and SHA-256 were checked.

## Dedicated Fabric server

- Minecraft 1.21.1, Fabric Loader 0.19.5, Java 21.
- Dependencies: Fabric API 0.116.17, Fabric Language Kotlin 1.14.1, Fzzy Config 0.7.7, Data Attributes 3.0.3, full WorldEdit 7.3.8 production distribution.
- The alpha.5 Mojang-mapped core and optional adapter loaded successfully and reached `Done`.
- SQLite reopened at schema 1 without an unclean-shutdown warning. `/guardian status` reported a running writer and zero failures.
- The server shut down normally. Its SQLite configuration was preserved.

Earlier Guardian baseline checks also verified DuckDB startup on Fabric. WorldEdit's Maven artifact is only a compile dependency; the complete production jar is required at runtime.

## Dedicated NeoForge server

- Minecraft 1.21.1, NeoForge 21.1.256, Java 21.
- Dependencies: Kotlin for Forge 5.12.0, Fzzy Config 0.7.7, Data Attributes 3.0.3.
- The core loaded without a separate common jar. NeoForge discovered both bundled JDBC drivers.
- SQLite startup, normal shutdown, and restart passed. The versioned alpha.5 checkpoint reopened schema 1 without an unclean-shutdown warning.
- DuckDB startup and shutdown passed at schema 1.
- `/guardian status` reported a running writer with zero failures for both backends.
- NeoForge initialized the default permission handler and Guardian's registered nodes.
- The server was stopped normally and its original SQLite configuration restored.

## Acceptance still needed

No player was connected during these smoke tests. Player block placement and breaking, inspector interactions, actual rollback application, and WorldEdit edits still need a player-driven acceptance session. Compilation and idle server startup do not establish those gameplay paths.

The optional WorldEdit adapter currently targets Fabric. Step 4 container and item transactions have not yet been implemented. No fluid or entity logging was added.

Loom still emits notices about OneDrive and the JDBC drivers' four-part Maven versions. These did not prevent build, test, or startup success. No Kotlin return warnings or deprecated WorldEdit constructor warnings remained.

The requested humanize-text pipeline was reviewed but not executed because its LLM and Niutrans credentials are unavailable. Documentation was edited directly and checked against the implemented commands and dependencies.
