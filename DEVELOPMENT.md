# Developing Guardian

Guardian is built with Kotlin, Java 21, and Gradle 9.8.0. Java is reserved for mixins and low-level hooks. The Fabric build uses Loom 1.17.21; NeoForge uses ModDevGradle 2.0.148. Both use official Mojang mappings. Cloche is not needed for this layout.

## Baseline

The imported Step 3 Fix 1 source is the baseline. Its two DuckDB lookup failures were reproduced and repaired before the rename. All 38 tests then passed, a repeated `build` reused the configuration cache, and the separate WorldEdit adapter build passed.

The core and optional WorldEdit adapter remain separate artifacts with separate licenses. Do not introduce WorldEdit imports into the core.

## Implemented milestones

1. Completed: the platform-neutral domain, storage, queues, filters, and rollback decisions now live in `common`. Minecraft code now lives in the shared `minecraft` source directory; loader lifecycle, events, and permissions live in the corresponding platform module.
2. Implemented: NeoForge 1.21.1 builds from the same Mojang-mapped Minecraft sources. Dedicated-server startup, status, shutdown, and restart are smoke-tested. Player-driven capture, inspection, and rollback acceptance remains pending on both loaders.
3. Implemented: immutable registry-aware item snapshots and correlation of all changed logical slots from an accepted block-container menu click.
4. Implemented: atomic container persistence and location queries on SQLite/DuckDB. Automated codec, correlation, persistence, retry, and migration tests pass. Exercise actual clicks, shift clicks, offhand swaps, splits, drag actions, cancellation, and restart recovery with a player before claiming gameplay acceptance. Close-time cursor returns, standalone drops, and offhand swaps now share that pipeline. Inventory-screen capture includes populated vanilla crafting grids, recipe-book placement, accepted result takes and close-time returns. Creative capture brackets accepted player-slot writes. Extended crafting layouts, outputs thrown into the world, direct creative drops, other automated transfer mechanisms and unsupported menus remain for later slices. Block-to-block hopper push/pull correlation is now implemented behind an opt-in switch; it observes physical inventories across vanilla and NeoForge capability paths.

5. Implemented: alpha.10 adds bounded, read-only item rollback planning. It checks count/component conservation and current inventories without slot writes. Alpha.11 adds persistent journal entries, exclusive journal claims, guarded transitions and interruption detection. Alpha.12 checks all persisted owner history, recorded block changes and journal claims before preview or preparation. Alpha.13 waits for a committed/flushed accepted audit prefix before preview lookup. Alpha.14 adds read-only recovery commands, live owner identity checks and a final preview audit/history recheck. Alpha.15 invalidates active observations on captured owner changes, including rejected queue submissions. Alpha.16 adds a common saved-state completion protocol and failure/restart tests. Alpha.17 adds a real region readback path and a read-only saved-block comparison command. Alpha.18 adds bounded player-file readback and main/armor/offhand decoding to the same saved comparison. Alpha.19 adds bounded owner reservations and mutation permits in common code. Alpha.20 installs a server-owned registry and pre-transfer hopper guards, verified on both loaders. Alpha.21 adds tested common menu mutation and cleanup policies. Alpha.22 connects click, recipe, player action, creative packet, opening and cleanup guards and verifies them with synthetic server players on both loaders. Alpha.23 pauses reserved vanilla furnace ticks and invalidates reservations before container state/identity changes and chunk unload callbacks. Connected-client visual acceptance, other automation and unsupported modded mutation paths remain pending. Commands still do not acquire reservations. The completion save port is not implemented or connected to commands. Live save reconciliation, exclusive gameplay coordination and item rollback apply remain pending. See [item rollback](docs/ITEM_ROLLBACK.md).

Fluids and entity logging remain outside this stage. Do not claim a loader or transaction path is supported until it passes runtime checks.

## Build checks

```bash
./gradlew clean build --no-daemon --warning-mode all
./gradlew build --no-daemon --warning-mode all
./gradlew build --no-daemon --warning-mode all
./gradlew :worldedit-adapter:build --no-daemon --warning-mode all
```

`clean build` and `build` have different task graphs. The first plain `build` stores its own configuration-cache entry; repeating that exact command verifies reuse.

Loom warns about four-part JDBC versions when generating nested mod metadata. Those are Maven versions, not Fabric semantic versions. Preserve the real driver versions when addressing metadata warnings.

## Repository hygiene

Keep server worlds, logs, local credentials, generated build outputs, and source archives outside Git. The Gradle wrapper jar and Guardian icon are intentional source assets. Do not publish or push automatically during local testing.

## Optional libraries

[Remnant](https://github.com/BareMinimumStudios/remnant) stores registered player data while players are offline. [Crunch](https://github.com/boxbeam/Crunch/tree/rewrite) evaluates mathematical expressions. Neither is needed for the verified block audit path; add a dependency only when a concrete feature uses it.

## Documentation editing

[humanize-text](https://github.com/lynote-ai/humanize-text) was reviewed as requested. Its pipeline requires an LLM provider key and a Niutrans key. It has not been executed in this checkpoint because those services are not configured. Documentation was edited directly for readability and checked against the current implementation. Keep commands, configuration names, API identifiers, and version numbers intact in any later rewrite.

The current Step 4 checkpoint is version `0.4.0-alpha.34+1.21.1`. It upgrades storage to schema 8 for owner history checks and item rollback tracking and retains GCT2 transient-grid encoding while preserving existing GCT1 item history and block payloads. Standard builds bundle SQLite only; DuckDB is an explicit optional build variant.

Capture uses [MixinExtras WrapMethod](https://github.com/LlamaLad7/MixinExtras/wiki/WrapMethod) and WrapOperation so hooks can chain with other mods. A player-scoped lease suppresses nested actions; the original operation still runs when no capture is possible. Close capture retains the original menu after vanilla resets the active menu.

## IntelliJ dependency tools

MixinMCP's Gradle plugin 1.5.0 is applied to Fabric, NeoForge and the WorldEdit adapter. Use IDEA 2026.2 or newer with the MixinMCP and MCP Server plugins enabled. Run genDependencySources through an IDEA Gradle configuration after dependency changes, then sync the project. Dependency caches stay outside Git and are not included in the mod jars. See https://github.com/muon-rw/MixinMCP for installation.

## Dispenser and dropper coordination

Alpha.24 pauses dispenser and dropper activation while any inventory reservation exists. Custom dispense behaviors and NeoForge capability handlers can affect owners beyond their visible targets, so a source-only check would not establish exclusion. Guards run before item reads or callback execution, independent of audit logging. Skipped activations are not replayed; a new activation works after release or expiry. Commands still do not acquire reservations and item apply remains disabled.

## Brewing and crafter coordination

Alpha.25 extends the common automation gate to brewing stand ticks and crafter activation/ticks. Any reservation pauses these paths before brewing hooks, recipe assembly, ingredient consumption, output/remainder insertion or ejection, and timer changes. The gate performs no inventory reads or chunk loads. Normal operation resumes after release, invalidation or expiry; skipped crafter activations require a fresh redstone activation. This does not add brewing/crafter audit capture or recipe rollback support.

## Player lifecycle coordination

Alpha.26 invalidates affected whole operations before disconnect saving, respawn, death, dimension changes and inventory copying. Lifecycle work continues normally. Known participating menu owners are included; unknown menu ownership conservatively invalidates all remaining operations. This reuses the common cleanup policy and does not add player/entity capture or verified rollback completion saves.

## Item use and direct inventory changes

Alpha.27 pauses ServerPlayerGameMode item use and use-on-block before vanilla/NeoForge callbacks whenever any reservation is active. These callbacks may change inventories beyond the held stack, so their ownership cannot be inferred from that stack. Refused actions refresh the authoritative inventory state. This does not certify connected-client block prediction or item uses already in progress.

Direct Inventory setters, insertion/removal, loading/copying, clearing, dropping, hotbar picking and returned-item methods invalidate the player's whole operation before proceeding normally. Modifying clearOrCountMatchingItems invalidates all operations because it can also touch an extra container and invoke a predicate. Vanilla count-only queries preserve contents and reservations. Arbitrary predicates with side effects are not certified.

Both overloads of insertion and returned-item methods use explicit descriptors so Fabric remaps each selector independently. Direct mutable stack/list writes and arbitrary modded writes and a trusted apply-write permit remain pending. Item rollback apply stays disabled.

## Ongoing player item use

Alpha.28 guards LivingEntity item-use entry points for ServerPlayer instances. Direct start, the outer continuation tick, direct use ticks and completion pause while any reservation is active. Guarding the outer tick also prevents NeoForge continuation callbacks from running first. Start refusal refreshes the authoritative inventory state; paused ticks do not send a resynchronization every tick.

Active release and stop remain available. They revoke all reservations before original callbacks because release, finish and NeoForge onStopUsing behavior may affect inventories beyond the held stack. Inactive release/stop does not revoke reservations. Cleanup then proceeds normally, including charged bow release. A successful completion can reach stop cleanup and conservatively cancel reservations acquired by callbacks during completion.

This is coordination, not logging of consumption or projectiles. Non-player entities use their normal paths. Synthetic-player checks cover food, milk-bucket returns and bow release on both loaders; connected-client use animations and modpack callbacks remain acceptance work. Direct mutable stack/list writes, arbitrary replacement methods and verified save/apply remain pending. Commands still do not acquire reservations; item rollback apply remains disabled.

## Equipment, durability and inventory-item ticks

Alpha.29 invalidates all reservations before ServerPlayer equipment replacement, shield damage, the shared armor-damage entry point and player-attributed ItemStack durability overloads. Equipment callbacks, enchantment processing and NeoForge armor/damage hooks may mutate inventories beyond the equipped slot, so affected ownership cannot be bounded to that slot. Invalidation cancels coordination and then lets the original operation proceed. Combat and break handling are not denied.

Explicit descriptors cover the shared LivingEntity/equipment-slot and ServerPlayer/callback durability overloads. NeoForge's additional LivingEntity/callback overload has a separate loader-specific guard. Damage with a null or non-player actor is not attributed to a player inventory; direct stack/component writes without an actor remain unverified.

ItemStack.inventoryTick pauses for ServerPlayer-owned ticks during any reservation, before pop-time changes and item callbacks. It checks the actor's actual level rather than trusting the level passed by the caller. Ordinary Inventory.tick reaches this guard for its nonempty stacks; direct calls to Item.inventoryTick or arbitrary modded replacement methods remain outside this coverage. Non-player tick/damage paths proceed normally.

Live synthetic-player tests cover main/offhand and armor replacement, inherited hand setters, armor/helmet/shield damage and breaks, direct durability calls and callback order, plus pause/resume through direct stack and ordinary inventory ticks. These are coordination hooks, not equipment/damage history or entity logging. Modpack callbacks, connected-client effects, direct mutable stack/list writes, trusted write permits and saved-state completion remain pending. Item rollback apply stays disabled.

## Direct block-container changes

Alpha.30 invalidates all reservations before loaded BaseContainerBlockEntity slot setters, removals, clearing, RandomizableContainerBlockEntity loot-table/seed setters and hopper/furnace overrides. Final BlockEntity NBT reload and component-application entry points use the same guard for base-container instances. Direct changes then proceed normally, including commands and integrations using these APIs.

The guard uses the existing server/thread binding without item reads, chunk loading or loot unpacking. Detached containers and off-thread mutations remain outside the coordination contract. The idle common registry skips expiration clock reads and scans when it has no operations. Arbitrary item/loot/component callbacks can affect inventories beyond the target block, so direct writes conservatively cancel every pending operation. An unrelated hopper transfer that reaches a guarded setter can therefore cancel a reservation while continuing normally; a future apply driver must recheck its lease and abort.

Ordinary item reads and serialization with no pending loot table preserve reservations. Existing read paths that unpack loot, direct getItems/setItems or mutable stack/list/component writes, arbitrary overriding methods and loader capabilities bypassing these APIs remain unverified. Loaded containers with deferred loot are still ineligible for saved-state reconciliation; the setter hooks do not certify every loot callback.

Both loaders exercise the eight existing supported inventories: barrel, chest, hopper, dispenser, dropper, furnace, blast furnace and smoker. This does not widen the owner whitelist or add direct-command mutation history. Trusted write permits and actual save/apply remain pending; item rollback apply stays disabled.

## Explicit write scopes

Alpha.31 adds ItemWriteScope in common code. A synchronous callback receives an explicit permit for one owner of a current lease from the same registry. The permit checks its target, scope identity, thread and lease on every requireCurrent call. Ordinary allowsMutation calls remain denied for the reserved owner; no ambient authorization is exposed to callbacks.

Permits expire when their callback exits and cannot be reused in a later scope or deferred callback. Nested writes on the same scope instance are refused without replacing its active permit. The result is returned only after final lease validation. Callback failure cancels the original whole operation and propagates the exception; cleanup cannot invalidate a newer replacement operation with the same ID. Expiry and server stop retain their own terminal states.

A driver must own one scope instance and pass permits explicitly to audited setter operations. This class does not write inventories, compare live identities, undo partial changes, persist journal transitions or authorize asynchronous work. Different scope instances are not a global reentrancy fence. Partial writes would still need the journal/recovery protocol and verified saves.

Eleven contract tests cover owner/registry mismatch, escaped/deferred permits, release, nesting, failure cleanup, replacement identity, expiry, stop and wrong-thread access. The class is packaged in both loader builds but remains disconnected from gameplay hooks. Connecting it requires a setter protocol that does not grant permission to arbitrary callbacks, plus remaining mutation exclusion and actual saved-state completion. Item rollback apply stays disabled.

## Reserved single-slot setter integration

Alpha.32 connects explicit write scopes to an internal Minecraft single-slot writer. It accepts one current lease, an exact inventory object, a logical slot and immutable expected/replacement snapshots. It checks live identity, supported class, deferred loot, stack limits and the before image, restores components through the registry-aware codec, calls the ordinary setter, then verifies the consumed hook sequence, current lease, live identity and resulting snapshot.

The setter ticket matches container identity, slot, restored stack identity and ordered entry points. Barrels/chests/dispensers/droppers consume randomizable and base entries; hoppers, furnaces and players consume their dedicated entry. The last entry removes authorization before setter side effects. Other guarded writes retain their cancellation behavior. Callers cannot supply a callback to this writer or borrow its ticket. Normal player setters revoke their operation; normal block setters revoke all operations.

Supported owners remain exact vanilla Inventory instances belonging to the current registered ServerPlayer, and exact loaded barrel/chest/hopper/dispenser/dropper/furnace/blast-furnace/smoker block entities. Double chests are written per physical half. This does not authorize CompoundContainer wrappers, temporary grids, cursors, brewing stands, crafters or modded inventory classes.

This is not a transaction, save acknowledgement or recovery driver. Setter side effects can occur before a postcondition failure; failure cancels the reservation but does not undo writes. A future apply driver must journal before the first write, verify full owner images, synchronize connected clients and reconcile actual saves. Unguarded mutable stack/list/component writes and arbitrary mod callbacks remain acceptance gaps. Commands never invoke this primitive or acquire reservations. Item rollback apply stays disabled.

## Journal-confirmed apply driver

Alpha.33 adds an internal common ItemApplyDriver and AsyncItemApplyJournal adapter. The driver starts from a validated PREPARED record. It checks complete live owner images, confirms the same prepared journal, waits for a committed PREPARED-to-APPLYING transition, and reads back the matching APPLYING record before any setter is called. Journal requests run on a caller-owned bounded worker executor; the adapter refuses inline execution on the inventory thread. Advance never waits on an incomplete future and performs at most one setter.

The driver composes reverse-history changes into one final write per changed address and omits net no-op addresses. It preserves a complete baseline including unchanged slots, compares that full image between writes and after each setter, rechecks exclusive ownership, then requires a final fresh APPLYING journal check. Only a WRITTEN result publishes an applying record for save handoff. WRITTEN does not acknowledge persistence or change journal claims; ItemSaveCompletion still requires actual saved-state verification.

Failure, stop and the ten-second deadline leave journal phases and claims intact. Pending journal futures are not cancelled and may still commit intent after the driver stops. A setter can change items before throwing; attemptedSlots counts attempts, not proven writes. No partial operation is automatically undone or replayed. An APPLYING operation reopens as RECOVERY_REQUIRED under the existing journal startup policy.

ItemApplyPort is a trusted contract requiring full inventory images and verified exclusive identities. Guardian does not yet provide a production Minecraft implementation of that contract. Reservations and the tested setters do not establish complete mutation exclusion. Private live tests connect the driver to real setters and SQLite under isolated controlled server-thread conditions; they do not certify arbitrary mods, save completion or connected clients. Full-owner history/preflight, remaining mutation paths, connected-client synchronization, platform scheduling and actual save/recovery handoff remain pending. Commands do not invoke the driver and item rollback apply stays disabled.

## Bound live inventories and actual save readback

Alpha.34 adds MinecraftBoundInventories, created through the installed server coordination registry. Each current lease can own one session, with at most 32 sessions. Binding rejects foreign/released leases, missing dimensions/chunks/players, unsupported inventory classes and sealed loot without loading chunks or unpacking loot. Sessions pin exact inventory, level and chunk objects, block state and logical slot count. Player inventories must belong to the current registered player. A read captures all persistent slots under the existing 2048-slot/16 MiB limits; writes use the audited reserved setter.

saveAndReadBack saves one pinned owner and compares its complete saved inventory to the captured live image. A narrow ChunkMap invoker queues a dirty loaded chunk, then the existing reader flushes queued writes and reads region data outside pending-write caches. A PlayerList invoker uses the ordinary player save path, followed by the bounded current .dat reader. Saved block type, player UUID, coordinates, data version, slot layout, components and counts must match. All bound live owner images and identities are checked again on the server thread before returning a result.

Only one save can be pending in a session. Closing it fails pending results without cancelling writes that may already be queued; it does not release the caller's lease. Server coordination shutdown closes sessions and the shared player reader. Identity, content, save/readback and budget failures return no successful saved image. A save can still have occurred before a later check fails. No journal phase or claim is changed here.

This is an identity/write/persistence primitive, not an implementation of ItemApplyPort.isExclusiveAndCurrent or ItemSavePort's complete exclusion contract. Snapshot comparisons detect visible changes but do not prevent arbitrary raw or off-thread writes. The apply driver, save-completion coordinator, journal worker, client synchronization and full mutation exclusion still need a production orchestration layer. Item rollback apply remains disabled.

## Asynchronous save completion

Alpha.35 adds AsyncItemSaveCompletion and AsyncItemSaveJournal. The driver polls futures on its creating thread and calls inventory save/readback there; journal reads and phase transitions use a separate worker. The worker must be bounded and reject excess submissions without a caller-runs policy. Inline journal execution is refused. Reads verify the complete journal identity and payload before each owner save, before completion and after an acknowledged transition. The worker checks the record again before its phase compare-and-set. Journal records must remain immutable within a phase, as they do in Guardian storage; concurrent journal edits are not supported by this adapter.

Every affected owner must return actual saved restored contents under the trusted ItemSavePort contract. Failed readbacks, stale records, missing exclusivity, timeouts, rejected work and failed acknowledgements leave the driver unresolved. The driver never releases leases or retries a completion. Once a completion request has been submitted, stop or timeout cannot retract it: completionAttempt remains available for the host to drain and the persistent journal must be reconciled. A positive acknowledgement alone is insufficient; the driver reads a matching COMPLETED record before reporting success.

The host must retain complete exclusive coordination while a completion request is pending, including after stopping the driver. MinecraftBoundInventories.isCurrent does not establish that contract. No production adapter connects the bound sessions to this driver yet. Full mutation exclusion, orchestration shutdown/reconciliation and client synchronization remain prerequisites; item rollback apply stays disabled. The original synchronous ItemSaveCompletion remains a platform-neutral protocol reference and is not called by the server.

## Bounded journal worker and shutdown drain

Alpha.36 adds ItemRollbackJournalWorker, a shared single-thread worker for the apply and save journal ports, with a queue of 32 requests and rejection on overflow. Requests and close belong to the creating driver thread. Journal operations run only on the worker, never through a caller-runs fallback. Completion requests validate bounded recovery records and compare the complete immutable record before the phase CAS. The caller owns the backend and must keep it open until drain.

Close sets the worker to stopped before notifying callbacks. New work is rejected and queued requests fail without journal access. Requests that have already started are not interrupted or cancelled: their actual results remain observable. The drained stage settles only after all admitted requests have terminal results. Returned stages cannot be cancelled in a way that hides or cancels the underlying operation. Drain establishes that this worker has no outstanding journal access; it does not establish successful commits or reconcile failures. A transition may commit before an acknowledgement fails, so persistent state still decides the outcome.

This worker connects the common asynchronous protocols to bounded database execution, not to live inventory mutation. ItemOwnerCoordination still expires reservations after ten seconds, and invalidation/shutdown can release them. It must not be used as the promised complete exclusion while a completion is pending. Before production apply can be enabled, the host needs mutation exclusion that survives the pending-commit drain, bound-session lifecycle handling, persistent outcome reconciliation and client synchronization. No automatic TTL extension or blanket mutation bypass is introduced. Item rollback apply remains disabled.

## Owner retention until journal drain

Alpha.37 adds Lease.retainUntilJournalDrained(worker). A current lease can register one running journal worker before submitting journal mutations. Its hidden drain-stage copy cannot be cancelled by callers. Owner entries and operation capacity remain occupied until the worker closes and all started journal results settle. The registry polls drain on its owning thread; worker callbacks cannot release entries directly.

Expiry, close, owner/chunk/global invalidation and registry stop still revoke the lease normally. A revoked retained lease is no longer current and cannot authorize reserved writes, but its owners cannot be acquired or used by ordinary coordination checks until drain. A stopped registry continues reporting retained entries and refuses mutation. Once drain is observed, revoked entries detach. A still-active lease whose worker drained before the original deadline keeps its ordinary lifecycle; retention never extends its active ten-second deadline. One worker can retain several operations, all within the existing 32-operation/32-owner limits.

The host must close the worker and retain the registry/binding through drain. A stuck running journal call deliberately retains entries rather than silently permitting reuse. Drain is not commit success: persistent outcomes, including lost acknowledgements, still need reconciliation. No live Minecraft code registers retention yet. Several mutation hooks invalidate reservations and then permit the normal write, and uninstall currently detaches the binding. Those paths need explicit physical mutation denial and ordered shutdown before this registry primitive can support a trusted exclusive apply/save port. Raw/off-thread mutations and connected-client synchronization also remain prerequisites. Item rollback apply remains disabled.

## Block API denial during journal retention

Alpha.38 connects pending journal retention to main-thread block-inventory API guards. Ordinary setItem, removeItem, removeItemNoUpdate, clearContent and randomized loot-table/seed setters are cancelled before their existing mutation hooks when journal retention is present. Removals return ItemStack.EMPTY; void operations do nothing. This conservative guard covers the BaseContainerBlockEntity and RandomizableContainerBlockEntity methods plus hopper/furnace overrides, including the eight supported physical inventory types. It pauses these APIs for unrelated loaded block containers too, because callbacks can have unbounded owners. Transfer/menu entry points and all loaded furnace ticks pause during retention too, so unrelated work cannot partially process a move around a blocked destination API. Existing behavior resumes after actual worker drain.

The exact audited setter ticket chain remains usable during retention, including Randomizable/Base delegation. Ordinary writes cannot borrow it. Revoked retained entries still activate denial until the owner thread observes drain. No gameplay command registers retention or calls apply; ordinary logging without retention keeps its existing behavior.

tryUninstall returns false without stopping the registry, closing sessions or removing the binding while journal retention is pending. The host must close its worker, poll drain on the server thread and retry. The legacy uninstall entry point refuses premature detachment. Once retention has drained, ordinary stop/session cleanup and callback protections still run.

This is a bounded API slice, not complete exclusion. Player inventory writes, player lifecycle, block/chunk replacement, NBT/component loads, loot unpacking through reads, direct stacks/lists, unsupported overrides and off-thread mutation need further guards or a conservative refusal contract. The application still needs trusted apply/save integration, persistent outcome reconciliation, shutdown scheduling and connected-client synchronization. Item rollback apply remains disabled.

## Direct player slot protection during journal retention

Alpha.39 refuses ordinary player Inventory.setItem, removeItem(slot, count), removeItemNoUpdate, removeFromSelected and identity-based removeItem(stack) calls while journal protection is retained. Removals return ItemStack.EMPTY; void calls leave the inventory unchanged. The guard runs before the existing invalidation hook. The exact audited player setter ticket remains usable. Revoked retained entries continue denying ordinary calls until actual worker drain; normal behavior resumes afterward.

This covers direct slot APIs only. Insertion and placeItemBackInInventory need a combined caller contract: Minecraft splits the offered stack before calling add, so refusing add alone could consume items without inserting them. Bulk clear, load, dropAll, replaceWith, hotbar rearrangement and lifecycle paths still need separate handling. These limits prevent this checkpoint from establishing complete inventory exclusion. Item rollback apply remains disabled.

## Bulk and hotbar protection during journal retention

Alpha.40 refuses Inventory.setPickedItem and pickSlot before changing the selected hotbar index or any stack. clearOrCountMatchingItems returns zero before invoking its predicate, accessing the extra container or changing the cursor while journal protection is retained. This includes limit zero: count-only calls still execute unknown predicates and can normalize the cursor. During this temporary refusal, zero means no operation was performed, not a certified empty inventory. Normal count-only behavior outside retention stays available, including ordinary reservations without pending journal protection. Revoked retained entries remain protected until actual worker drain.

Insertion remains pending. Cancelling add alone is unsafe because placeItemBackInInventory splits its input before calling add. Cancelling that parent alone is also insufficient: AbstractContainerMenu.removed clears the cursor unconditionally afterward, while other cleanup callers remove their source items before attempting the return. These callers need a combined ownership/cleanup contract before insertion can be refused safely. Existing insertion and cleanup continue invalidating coordination; they do not establish physical exclusion.

This milestone preserves full player, extra-container and cursor images during refused bulk calls and preserves damaged-item components during refused hotbar calls. Load, dropAll, replaceWith, clearContent, lifecycle/data paths, raw lists/stacks, unsupported overrides and mod transfer APIs still require coverage. Item rollback apply remains disabled.

## Player-menu eligibility for bound inventory work

Alpha.41 requires a player to use their exact vanilla inventory menu with an empty cursor, empty 2x2 crafting grid and empty result slot before binding or audited slot writes. The existing exact layout checks are reused without closing a menu, returning items, evaluating recipes or loading anything. Active container menus and temporary items are refused without changing persistent or temporary contents. A refusal during initial binding does not acquire a session or consume the existing lease.

Bound sessions recheck this state before reads, writes, saves and saved-image completion. A detected player identity/menu eligibility failure permanently rejects the session and invalidates its original operation. Other operations remain active. Journal-retained owner entries continue protecting pending work after this invalidation. Returning to an empty menu cannot revive the session or lease; a fresh lease and independently checked session are required. A pending actual save readback cannot acknowledge completion after such a rejection.

This is eligibility preflight, not coordinated cleanup or complete physical exclusion. ServerPlayer.tick can replace the menu after requesting closure, and InventoryMenu.removed clears result slots and returns crafting inputs. Cancelling close or insertion in isolation would not safely preserve all sources. Cleanup, disconnect/death/travel, unsupported menus and direct/mod transfer callers still need an ownership contract. The normal cleanup path continues invalidating coordination and proceeding. Item rollback apply remains disabled.

## Cleanup while journal protection is retained

Alpha.42 changes the shared menu/lifecycle cleanup policy during pending journal retention. It invalidates every active operation before resolving menu owners or entering vanilla cleanup. Cleanup can call mods or return items beyond visible owners, so an unrelated active permit is not safe to preserve in this window. Retained owner entries survive invalidation until actual journal-worker drain; stale permits cannot write or acknowledge a bound save. Released retained entries also keep this conservative policy active. After drain, ordinary selective cleanup policy resumes.

Vanilla cleanup still runs. The live menu-close fixture returns cursor and crafting input items normally, without loss, while invalidating the old bound session. This does not make cleanup physically exclusive: legitimate returned items can change the persistent inventory, and a late journal commit still needs reconciliation. Item rollback apply remains disabled. Disconnect, death, respawn and travel use this shared policy through existing hooks, but the new live retention case tests menu closure; the complete retained lifecycle/client matrix remains pending.

## Respawn inventory copies during retained journal work

Alpha.43 fixes a conflict between mandatory player respawn and the retained direct-slot guard. Vanilla restoreFrom copies inventory through replaceWith and setItem; refusing those setters would suppress the legitimate copy. A narrowly scoped wrapper now authorizes just that exact call chain. It pins the source and destination inventories, the same player UUID, all 41 source item references and the selected slot. Each setter must match its expected destination, slot and source object, in order. Its one-use ticket is consumed before the setter body, and both ticket and scope are cleared on failure or return. This keeps vanilla's component-bearing item objects intact without introducing a serialization limit into mandatory respawn.

The scope does not grant a rollback lease. Existing operations are invalidated before lifecycle callbacks, and ordinary setters and direct replaceWith calls remain refused while journal protection is pending. Live fixtures on both loaders exercise registered-player respawn and disconnect with an actual running journal request, full immutable snapshot comparisons, and a readback of the disconnect's actual current player file. Held owners and uninstall remain blocked until worker drain; fresh independent binding then succeeds without reviving an old session.

These are synthetic server players, not connected clients. Keep-inventory respawn and disconnect are covered; death/drop, dimension travel, mod callbacks and connected-client synchronization still need acceptance checks. Production retention registration, exclusive apply/save integration and late journal outcome reconciliation remain pending. Item rollback apply remains disabled.
