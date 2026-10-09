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

The current Step 4 checkpoint is version `0.4.0-alpha.32+1.21.1`. It upgrades storage to schema 8 for owner history checks and item rollback tracking and retains GCT2 transient-grid encoding while preserving existing GCT1 item history and block payloads. Standard builds bundle SQLite only; DuckDB is an explicit optional build variant.

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
