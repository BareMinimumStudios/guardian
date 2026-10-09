# Item rollback preview

Checkpoint: `0.4.0-alpha.34+1.21.1`.

This milestone waits for accepted audit writes and checks which recorded item transfers could be reversed. It does not change items. There is no item rollback apply command yet.

## Try a preview

```text
/guardian rollback-items preview t:10m r:5
/guardian rollback-items preview t:1h u:Cherry r:5 x:100 y:64 z:100
```

Use `guardian.rollback` permission, or vanilla operator level 2. Time is required. `user:` and `time:` are aliases for `u:` and `t:`. Coordinates must be supplied together; otherwise the center is your current position. The current dimension is used. Fabric WorldEdit adapter users can use `r:#worldedit` for an existing cuboid selection. The default radius and maximum radius come from rollback settings. Result paging and ordering parameters are not accepted for a preview.

Both physical block endpoints must be inside the selected region. A player endpoint must be online with the ordinary inventory screen, an empty cursor and an empty crafting grid. Offline inventories are not read from player files.

## Reading the result

Eligible means that all observed changed slots match their recorded after-state, and reversing the transaction conserves item counts and components. The planner simulates transactions newest first on a private copy. A skipped transaction blocks older dependent history; independent inventories can still qualify.

Changed slots, missing or unloaded containers, sealed loot, busy players and endpoints outside the selection are skipped. The preview also checks persisted history outside your filters. If an excluded transaction touches a participating inventory at or after the earliest selected change for that owner, the candidate is skipped. An equally timed excluded record is also unsafe. This check includes player cursor and crafting actions through their player owner, and applies to the whole inventory rather than just matching slot numbers.

Recorded block history at or after the container's selected item history invalidates that position, even if the block change was rolled back. Unfinished journal reservations and previously claimed transactions are skipped as well. Sealed loot remains sealed and chunks are not loaded to inspect them. Shared-inventory records with identical timestamps are skipped because the stored history cannot establish their order.

Crafting, creative changes, drops, close-time returns, temporary cursor/grid addresses and item transformations are excluded. The preview does not turn a recipe into its ingredients or recreate a dropped item.

The preview checks at most 50 transactions, or the configured rollback record limit if lower, 32 logical inventories and 2,048 changed slots. Larger selections are refused instead of silently truncated. It reads participating slots from one inventory per server tick. Preview and recovery share one active check. Retained item payloads are capped at 16 MiB; an excessive observation is unavailable.

## Inspect unfinished journals

```text
/guardian rollback-items recovery
/guardian rollback-items recovery <operation UUID>
```

The same `guardian.rollback` permission or operator level 2 is required. Listing shows at most ten unfinished headers; click one to suggest its command. Their UUIDs are available for completion after listing. A specific check loads one bounded journal, observes participating slots one owner per tick, waits for the accepted audit prefix again and confirms the journal did not change. It times out after ten seconds. Unloaded chunks stay unloaded and sealed loot stays sealed.

| Observation | Meaning |
| --- | --- |
| ORIGINAL | Observed slots match their recorded state before rollback. |
| RESTORED | Observed slots match the planned restored state. |
| BOTH | Both states are identical; whether apply occurred is unresolved. |
| PARTIAL | Slots contain a mixture of original and restored states. |
| CONFLICT | Items or components match neither expected state. |
| UNAVAILABLE | An owner could not be read or its live identity changed. |

These commands never change items, advance phases or clear claims. ORIGINAL and RESTORED describe sampled live slots; neither establishes durable chunk/player saves. Owners are sampled across ticks, so the result is not an atomic inventory snapshot. Gameplay continues during the check. Treat partial, conflicting, unavailable and indistinguishable states as unresolved; no automatic replay follows a result.

## Captured activity during observation

Preview starts a watch after its initial persisted-history check; recovery starts one after loading and validating its journal. Each watch contains at most 32 persistent owners. A pipeline supports at most 32 active watches. The normal command services still allow only one item check at a time.

Every submitted item or block snapshot invalidates matching owners before the queue accepts or rejects it. A change to any slot affects the whole logical inventory. Cursor and crafting-grid changes affect their player's inventory owner. Repeated changes, including moving items away and back, never reset a watch. Other owners and dimensions remain independent.

The final result marks affected owners unavailable. Queue rejection does not hide captured activity from these watches. Completion, refusal, timeout and shutdown release the watch; writer failures invalidate outstanding handles. Registration, submission and inspection share a short ordering gate, with no storage calls or completion callbacks under that gate. Watches retain bounded owner identities, not item data or historical transactions.

This only covers snapshots submitted during the watched interval. Disabled logging, unsupported capture paths, failures before submission and other mod writes remain outside the guarantee. The watch does not lock an inventory, prevent a transfer or establish a disk save.

## What remains before apply

Observations collected over several ticks can become stale. A successful preview is not a reservation or a promise that a later apply will succeed. After reading owners, it repeats the accepted-prefix barrier and persisted-history check. It then checks captured live object identities, block state, container size, sealed loot and player menu identity. These checks detect observed owner changes but do not freeze contents, prove that no unlogged mutation occurred or cover changes accepted after the final barrier.

The persistent journal now records a bounded transfer chain atomically with inventory reservations and source transaction claims. Preparation verifies the source payloads against stored history and checks owner history, recorded block changes and existing claims inside the same database transaction. A second operation cannot reserve the same logical inventory or claim an already completed source. These reservations coordinate journal operations; they do not freeze ordinary gameplay inventories.

Journal phases are PREPARED, APPLYING, RECOVERY_REQUIRED, COMPLETED and CANCELLED. Only a PREPARED operation can be cancelled and release its source claims. Terminal phases release inventory reservations; completed source claims remain to prevent a second reversal. Transitions use an expected phase so stale callbacks cannot advance a changed operation. The future coordinator must establish saved-world durability before calling completion; the storage method itself is not proof that Minecraft saved items.

On startup, an APPLYING journal becomes RECOVERY_REQUIRED and retains its claims. It is not replayed. Recovery observations compare exact counts/components with original and restored slots. Partial, conflicting, missing and cyclic indistinguishable states remain unresolved. `/guardian status` reports `itemRecovery`, and startup warns if unfinished operations exist. Ordinary previews do not create journals. Recovery listing reads headers only and loads one bounded payload on request.

The audit-write barrier is available, but apply still needs gameplay coordination and a fresh barrier/history check at the moment of mutation, exclusive live inventory coordination at mutation time, plus recovery coordinated with durable world/player saves. The journal alone cannot make a multi-inventory Minecraft write atomic. Those checks will precede any slot writes. Player-driven cross-inventory acceptance and crash recovery are still pending; preview-only results do not establish those guarantees.

## Compare saved block and player slots

```text
/guardian rollback-items recovery saved <operation UUID>
```

This read-only check uses the same permission and bounded journal validation as the live recovery view. It supports persistent player inventory owners and physical vanilla block inventories: barrels, physical chest halves, hoppers, furnaces, blast furnaces, smokers, dispensers, droppers, brewing stands and shulker boxes. Unknown modded block layouts and temporary cursor/crafting owners remain unavailable.

The reader first flushes the owning chunk worker's queued writes. It then reads region bytes through that worker, bypassing its pending-write read cache. It does not serialize current live chunks, load chunks, unpack loot or advance journals. Unsaved live edits can therefore differ from this saved result. It processes one owner at a time and retains the existing ten-second observation deadline. A flush/read already in progress can finish after timeout or shutdown; stale callbacks are ignored.

Decoded chunk data is limited to 16 MiB. The decoder requires the current Minecraft data version, full chunk status, matching chunk/block coordinates, one matching block entity, a known physical slot layout and valid unique saved slot numbers. Exact item counts and Data Components use the same registry-aware canonical codec as live audit snapshots. Missing saved owners, incompatible data, unknown layouts, unreadable components and sealed loot are unavailable. Missing region files are not opened or created.

Player owners read the current `<UUID>.dat` in the server world's player-data directory, whether the player is online or offline. The reader does not log in a player, force a player save, use `.dat_old` or data-fix an older file. UUID and current Minecraft data version must match. Saved slots 0–35 map directly, armor slots 100–103 map to logical slots 36–39, and unsigned saved slot 150 maps to offhand slot 40. The vanilla Inventory list must exist; duplicate, invalid or unknown saved slot numbers are refused. Mod-specific inventory attachments are outside this layout.

File I/O uses one background thread and a queue of four. Encoded and decoded player files are each limited to 16 MiB. File identity, size and modification time are checked across the read; a detected change is unavailable. Decoding item components happens on the server thread with its loaded registries. Missing, corrupt, wrong-UUID and incompatible-version files are unavailable. Shutdown fails outstanding reads, rejects new work and ignores late results; it does not write or delete player files.

Saved owners are still sampled separately and gameplay is not frozen. A RESTORED saved comparison is not permission to complete or replay a journal. Exclusive coordination, live-owner identity checks and verified save acknowledgements must accompany a future completion adapter. The command does not promise protection against power loss.

## Saved-state completion protocol

The common `ItemSaveCompletion` driver is implemented and fault-tested, but no Minecraft `ItemSavePort` is installed and no command calls it. It has no inventory setters. This is sequencing infrastructure for a future coordinated apply/recovery path, not runtime save acceptance.

The driver accepts only a bounded, coherent APPLYING or RECOVERY_REQUIRED journal. A trusted platform port must hold exclusive ownership of every participating inventory, keep their identities and restored contents unchanged, finish each owner's save/flush, and return an immutable readback from saved storage. Reading current slots or pending-write cache data cannot satisfy that contract.

Each advance starts or polls at most one owner save. It never waits on an incomplete future. Saved participating slots must match exact restored counts and components. After every owner succeeds, the driver rechecks the journal and exclusive ownership and uses an expected-phase transition to COMPLETED. Completion releases inventory claims through the existing atomic journal method; source transaction claims remain.

Failure, missing slots, mismatching components, ownership loss, changed journals, timeout or stop leaves the driver unresolved. It does not replay items or clear claims. A stop does not cancel a write already in progress; late results cannot advance this stopped driver. If the terminal database write succeeded but its acknowledgement failed, completion remains uncertain and a later driver must reload the journal. APPLYING operations become RECOVERY_REQUIRED on restart through the existing startup handling.

The driver is thread-confined. A future platform integration must marshal exclusive-ownership checks and inventory serialization on the server thread, perform journal and file I/O off it, and serialize driver advances without holding a server tick waiting on disk. The ten-second deadline is checked on advances; it does not interrupt a blocking adapter or database call.

Inspection of the pinned Minecraft 1.21.1 classes established two constraints: `PlayerDataStorage.save` catches save exceptions and logs them before returning, and `IOWorker.loadAsync` can return `PendingStore.copyData` without reading the region file. A method return or generic chunk read is therefore insufficient. Actual flush/readback adapters, gameplay exclusion, process-crash tests and any power-loss guarantees remain separate work.

## Owner index migration

Schema 8 records each transaction's changed logical owners and timestamp. The index is part of the same atomic audit write as the source payload and locations; a retried UUID cannot add different owners. Existing records are decoded in pages of 128 during the one-time migration, retaining only owner keys between rows. This startup work scales with existing item history. Source payloads are unchanged. A corrupt payload aborts the migration instead of leaving a partial index or certifying incomplete history.

Back up the stopped database before upgrading. Config version 6 and GCT1/GCT2 payload formats stay the same. SQLite is the standard backend; optional DuckDB follows the same migration and checks. The nonpersistent memory backend cannot establish these guarantees and refuses item rollback preview.

## Accepted audit prefix

Before fetching history, preview requests a receipt for the pipeline's current accepted-entry count. The writer acknowledges that prefix after appending its exact pending batches and successfully flushing storage. Later submissions do not have to finish for an older receipt to complete. The server thread waits through a callback and continues ticking.

There can be at most 32 pending receipts per pipeline. Cancellation removes the waiter; storage flush failures and writer lifecycle failures reject it. Preview has a ten-second limit while waiting for the receipt and history preparation. Shutdown cancels its session and ignores late callbacks. The writer checks pending receipts between batches and polls an idle queue at most every 100 milliseconds.

This receipt covers accepted pipeline entries, not rejected captures, backpressure losses, other work still waiting to submit or future gameplay changes. It is not an inventory reservation or a world/player save receipt. Recovery apply will need those separate checks and a fresh accepted-prefix barrier while participating inventories are coordinated. Callbacks must schedule their work on the appropriate executor; they run outside the pipeline ordering gate.

## Inventory-owner reservations

Alpha.19 adds `ItemOwnerCoordination` in common code. It is a thread-confined reservation registry for future platform hooks. No Minecraft service acquires a reservation yet, and commands still perform read-only comparisons. A reservation does not by itself establish exclusive gameplay access or satisfy `ItemSavePort`.

Acquisition reserves the entire validated owner set or nothing. Each operation can contain 1–32 unique persistent block/player owners, with at most 32 active operations. Duplicate operations, overlaps, temporary cursor/grid owners and oversized requests are refused. Owner sets are immutable copies.

Future mutation hooks can check an owner's reservation with `allowsMutation`. Ordinary access to a reserved owner is refused. Coordinated writes require the exact active lease object for that owner, not an operation UUID. A stale, expired or foreign permit is refused even if that owner is currently unreserved. This prevents a late callback from falling back to ordinary access.

An invalidated owner revokes every owner in its operation. Close, timeout and stop release all reservations without inventory writes or journal transitions. Closing an old lease cannot release a replacement with the same operation ID. The ten-second monotonic deadline is checked on registry/lease calls, including across `nanoTime` wraparound; no background timer is installed. A future driver must advance/check regularly and revalidate immediately before mutation. Shutdown refuses new work. All operations, including lease reads and releases, require the registry's owning thread.

Saved-readback interruption tests verify that reservation loss leaves the save driver unresolved. A late successful read cannot advance that stopped driver or disturb a replacement reservation. This is common-protocol acceptance, not a live server exclusion test.

MixinMCP inspection of the pinned classpaths confirms that vanilla hopper transfer methods remove and mutate stacks directly, while NeoForge can take its capability insertion hook before the vanilla path. Furnace server ticks also consume fuel and alter output independently of menus. Blocking clicks alone is insufficient.

The next platform slice must establish coverage for physical container identities and both transfer endpoints, menu access, player actions, ticking inventories, replacement/unload and player disconnect. Unsupported modded mutation paths must refuse apply. Only then can a Minecraft save port combine reservations, identity/content checks, real save/flush readback and the final journal transition. Current observation watches remain useful for detecting captured activity, but they do not block it.

## Hopper reservation guard

Alpha.20 installs a server-owned reservation registry and checks hopper push/pull attempts before the existing audit wrapper. Commands still do not acquire reservations, and item apply remains disabled. The registry is stopped and detached during server shutdown.

With no reservations, the guard allows ordinary server-thread transfers without resolving endpoints or reading inventory contents. During a reservation it checks the hopper position, the attached/source block position and each connected physical chest half. Chest topology reads use already loaded chunks; the guard does not load chunks, read item stacks or unpack loot. A reservation at any participating physical owner refuses the entire attempt before the original transfer and capture code run. Release/expiry allows later attempts to resume. The guard is independent of all logging switches.

The common transfer gate accepts up to four unique physical block owners. Unavailable, failed or unsupported endpoint resolution refuses an attempt while reservations exist. Stopped gates refuse late work. Non-block hopper pull endpoints are currently unsupported and are refused during an active reservation; ordinary unreserved behavior remains available. Off-thread calls to the installed server are refused.

The same wrapper surrounds NeoForge's capability insertion/extraction fast paths. Runtime acceptance covers vanilla physical hoppers, barrels and double chests on both loaders, including a double chest across a chunk boundary. This does not certify arbitrary modded capability wrappers that redirect to inventories at other positions.

A temporary reflection-only test agent, kept outside the repository and mod artifacts, acquires/releases reservations on the server thread. Both loaders passed source, destination, hopper and opposite-chest-half tests with hopper logging off and on. Inventories remained unchanged while reserved and successful transfers resumed after release. Each enabled run recorded seven successful transfers; disabled runs recorded none. Fixtures used isolated databases and were removed afterwards.

Alpha.22 connects supported menu/player paths, described below. Alpha.23 adds vanilla furnace tick and loaded container replacement/unload guards. Other automation and unsupported mutation paths remain uncoordinated. These partial guards cannot satisfy `ItemSavePort` or justify applying/completing an item rollback.

## Menu coordination policy

Alpha.21 adds a common policy for player and menu owners. Mutation checks reject a reserved actor or participating owner. Unknown, failed or oversized menu resolution refuses access while reservations exist. Idle checks do not inspect menus.

Cleanup always remains available. Known cleanup invalidates whole operations touching the actor or resolved owners; unknown cleanup invalidates all remaining operations before items can be returned. Alpha.22 connects Minecraft hooks before accepting click prediction or performing cleanup. This covers the paths below and does not establish exclusion of every inventory mutation.

## Menu and player guards

Alpha.22 checks container click packets on the server thread before vanilla accepts client-predicted slot and cursor contents. Refused packets resend authoritative inventory/menu contents. Recipe-book placement, standalone drop/offhand-swap actions and creative slot/drop packets also check reservations before mutation. Guards operate independently of item transaction capture.

Physical owners are resolved without item reads, loot unpacking or chunk loads. Supported exact vanilla menu implementations are InventoryMenu, ChestMenu, HopperMenu, DispenserMenu, FurnaceMenu, BlastFurnaceMenu, SmokerMenu and CraftingMenu. Physical inventories are verified loaded vanilla barrels, chests, hoppers, dispensers/droppers and the three furnace variants. CompoundContainer parts are checked separately. Player Inventory slots use their actual player's UUID; crafting/cursor activity belongs to the acting player.

Opening a known physical provider checks reservations before createMenu can unpack loot or start opening an inventory. NeoForge's extended opening API shares this gate. An unknown provider, including an anonymous double-chest provider, is temporarily refused while any reservation exists. Unknown/extended menus and inventories follow the same conservative policy. When no reservation exists, these checks leave ordinary menu access alone and do not resolve owners. Reservations expire after ten seconds.

Closing is always allowed. Before vanilla returns carried items, known owners invalidate their whole operations. If ownership cannot be resolved, all remaining reservations are invalidated. This avoids stranding a cursor stack behind a cancelled close.

Runtime checks use synthetic server players and real Minecraft packet handlers on both loaders, with item transaction logging disabled/enabled. They verify rejected prediction, authoritative state resynchronization, valid recipe placement, block/combined-container ownership, pre-creation opening, safe close returns and resumed actions. Actual connected-client visuals, claim/modpack combinations, other inventory ticks and verified saves remain separate acceptance work. Commands still do not acquire leases and item apply remains disabled.

## Furnace and container lifecycle guards

Alpha.23 pauses the shared vanilla furnace server tick for a reserved physical inventory, covering furnaces, blast furnaces and smokers. The entire tick is skipped before fuel, input/output stacks, cooking timers or lit state change. It resumes normally after release or expiry, without catching up the skipped time. Unrelated furnaces continue ticking. This does not add smelting transaction logging.

Loaded chunk block-state writes invalidate an operation before vanilla replacement callbacks can mutate or drop items. An identical state write leaves the reservation intact. Block-entity installation and removal also invalidate operations at that location; installation can conservatively cancel an operation even when the same object is reinstalled. Connected chest halves are invalidated together when their old block state identifies a shared inventory.

The server unload callback invalidates every operation owning a block position in that dimension/chunk before block entities are cleared. Invalidating one participating owner revokes the whole operation, including its other block/player owners. Invalidation cancels coordination and allows vanilla lifecycle work to continue; it does not cancel a replacement or unload. A hopper power/enabled-state change can therefore cancel its operation and allow ordinary transfers to resume. The apply/save driver must recheck the reservation before proceeding.

The lifecycle hooks use no item reads, loot unpacking or chunk loading. They avoid extra block-state lookup when no reservations exist. They coordinate main-thread live gameplay; world-generation workers are left alone. Off-thread mod mutations, custom tick implementations and other automation still need an exclusion contract before item apply can be enabled. Region/player saving and final saved-state completion remain unconnected.

## Dispenser and dropper guards

Alpha.24 wraps the shared dispenser and dropper activation methods on Fabric and NeoForge. They refuse activation while any inventory reservation exists, including an unrelated block or player reservation. This deliberate global pause avoids treating a visible destination as the complete scope of a custom dispense behavior or NeoForge capability handler. The gate runs before random slot selection, item reads, entity creation or insertion callbacks. An installed server rejects off-thread activations and late work after coordination stops.

Without reservations, no endpoint resolution, inventory reads or chunk loads are added by these guards. Release, invalidation and expiry restore ordinary activation. A skipped scheduled activation is not queued for replay; a new redstone activation is required. Structural changes still cancel affected operations under the alpha.23 lifecycle rules.

Both loaders passed real redstone activation tests with item logging off/on. A reservation on a separate barrel held dispenser ejection, dropper ejection and dropper-to-barrel insertion unchanged; new activations succeeded after release. These tests verify the activation entry points, including NeoForge's normal inventory capability path. They do not certify modded code that bypasses those entry points or mutates inventories off-thread. Dispenser/dropper audit capture is not added. Remaining unsupported mutation paths, connected-client acceptance and verified saves still prevent enabling item apply.

## Brewing and crafter guards

Alpha.25 pauses the shared brewing stand server tick during any inventory reservation. The guard runs before fuel is consumed, a brew starts or progresses, potion/ingredient stacks change, or NeoForge brewing hooks execute. The timer stays where it was and resumes on the next ordinary tick after reservations end; it does not catch up skipped ticks.

Crafter activation uses the same global gate before creating recipe input, assembling a recipe or consuming ingredients. It therefore covers crafted output and remaining items whether they enter an adjacent container, pass through NeoForge's output handler or are ejected. The separate crafter block-entity tick is also paused before its animation counter or CRAFTING block state changes. Skipped scheduled activations are not replayed; crafting needs a fresh activation after release or expiry.

The broad pause is deliberate: recipe implementations and brewing/crafting hooks can mutate owners outside the visible machine and output destination. An unrelated block or player reservation therefore pauses these paths too. Without reservations, the guard performs no item reads, owner resolution or chunk loading. Installed-server off-thread calls are refused. Guards are independent of transaction capture.

Both loaders passed fuel/start and completion checks with item logging off/on, plus crafter ejection, insertion, recipe remainders and animation checks. Brewing made the expected potion and consumed one ingredient after release. A cake recipe delivered one cake and exactly three returned buckets while consuming its ingredients. These checks certify the wrapped vanilla entry points, not arbitrary modded code that bypasses them.

BrewingStandMenu and CrafterMenu remain outside the verified menu/owner whitelist and are conservatively refused during active reservations. No brewing/crafter history, transformation rollback, wider owner whitelist or item apply command is added. Remaining mutation paths, connected-client acceptance and actual saved-state completion still need verification before apply can be enabled.

## Player lifecycle invalidation

Alpha.26 revokes affected operations before PlayerList removes/saves a disconnecting player or begins respawn replacement. ServerPlayer death, dimension changes and restoreFrom inventory copying share the same cleanup policy. Copying checks both the previous and replacement player, so neither UUID's operation survives a transfer of inventory identity.

These hooks cancel coordination and then run the original lifecycle method. They do not prevent death, block disconnect saving or delay respawn/travel. Known player/menu owners invalidate their whole operations, including other participating block owners. Unrelated known operations remain active. If a transitioning player's menu cannot be resolved safely, all remaining reservations are invalidated. No inventory contents are read to resolve menu owners.

Invalidation occurs before NeoForge death/travel/logout hooks and respawn callbacks. A later hook that cancels an attempted death or travel does not restore the reservation. Same-dimension transitions are conservatively included because their callbacks can change inventories. The apply/save driver must treat an invalidated lease as unusable even if vanilla ultimately returns without a transition.

Both loaders passed synthetic-player checks with logging off/on. Inventory copying retained its normal contents, known/unknown menu handling followed the cleanup policy, death processing completed, disconnect wrote ordinary player files and respawn installed a new player instance with copied items. Same-dimension and real Nether transitions invoked their post-transition callbacks only after reservation invalidation. Synthetic players used real server methods; NeoForge used an in-memory connection channel for its networking metadata checks. Private tooling and generated player files remain outside the repository.

This does not log player sessions/deaths, certify arbitrary modded lifecycle replacements or establish verified rollback completion saves. Connected-client behavior, remaining item mutation paths and the trusted save port are still acceptance work. Item apply remains disabled.

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
