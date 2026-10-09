> Guardian checkpoint update (2026-10-07): the former ExProtect prototype now builds for Fabric and NeoForge 1.21.1 using official Mojang mappings. Pure audit code lives in `common`; shared Minecraft code lives in `minecraft`; loader hooks remain separate. See [validation](VALIDATION.md) for what has been exercised. Step 4 remains focused on container and item transactions.

# Guardian staged port plan

## Step 1 — Foundation — complete

Fabric 1.21.1 Kotlin/Java foundation, BML identity, Fzzy Config, permissions abstraction, immutable domain model, bounded writer, optional integration boundary.

## Step 2A — Persistent storage — complete

SQLite/DuckDB, schema versioning, normalized mappings, retry/idempotency, recovery markers, locking, storage contract tests.

## Step 2B — Player block capture — complete

Native block-state/block-entity snapshots plus reliable primary player place/break capture into persistent history.

## Step 2C — Lookup, inspector, first block rollback — complete

Asynchronous lookup, inspector, conservative block-only rollback, per-position conflict protection, and the ACTIVE/PENDING/ROLLED_BACK crash journal.

## Step 3 — WorldEdit integration and pressure tests — complete

Implemented:

- separate optional WorldEdit 7.3.8 adapter module
- BEFORE_CHANGE extent logging
- WorldEdit actor attribution and `WORLD_EDIT` cause
- streaming operation-aware bulk audit lane
- fail-closed reservation/operation ceilings
- abort-path audit flushing and post-mutation compensation
- `r:#worldedit` / `r:#we` lookup and rollback scope
- cuboid-only selection safety
- inclusive cuboid query support in memory/JDBC
- `/guardian status` bulk/writer diagnostics
- pressure/backpressure tests

Success target: a large WorldEdit edit cannot silently outrun the ordinary audit queue, and selection-scoped lookup/rollback reuses the same safe storage/history pipeline without introducing WorldEdit types into the core.

## Step 4 — Containers and item transactions — in progress

Implemented slices:

- Immutable Data Component-aware snapshots with registry-aware item codecs and atomic transaction storage.
- Accepted clicks in supported block-backed menus, normal close handling, player inventory drop/offhand actions, and validated creative slot changes.
- Optional balanced block-hopper transfers, including failed-push suppression and physical double-chest addresses.
- Filtered item history, five-record inspector pages, clickable navigation, and read-only inspection before normal claim callbacks.
- Vanilla 2×2/3×3 crafting grids, recipe-book placement, accepted result takes, close returns and recipe remainders in correlated transactions.
- Read-only container rollback preview with component/count checks, both-endpoint region checks and dependent-history skips.
- Persistent item rollback journal, source and inventory claims, interruption detection and bounded read-only recovery commands.
- Live owner identity observations and a final accepted-prefix/history recheck before returning previews.
- Bounded observation watches for captured owner activity, including queue rejections and player temporary-slot changes.
- Common saved-state completion sequencing with controlled-port fault tests and real SQLite claim/restart checks.
- Saved vanilla block-slot comparison from bounded region reads after queued-write flush.
- Bounded UUID-matched saved-player main/armor/offhand file comparison; coordinated save adapters remain pending.
- Filter-independent persisted-owner history checks, recorded block-change witnesses and journal-claim checks.
- Bounded asynchronous audit-prefix barriers before item preview history checks, with cancellation and lifecycle failure handling.

The current milestone checks sided furnace slots, loaded chunk boundaries, and controlled hopper load. See [validation](VALIDATION.md) for measured results and the remaining acceptance boundaries.

Bundle SQLite by default and retain DuckDB as an [optional build-time integration](DISTRIBUTION_SIZE.md). Alpha.19 adds bounded common owner reservations. Alpha.20 connects hopper push/pull guards to a server-owned registry and verifies active guards with logging off/on on both loaders. Alpha.21 adds common menu policy and alpha.22 connects supported vanilla menu/player paths, verified with synthetic players on both loaders. Alpha.23 covers the vanilla furnace tick and loaded container replacement/removal/unload callbacks. Alpha.24 conservatively pauses dispenser/dropper callbacks during any reservation. Alpha.25 covers brewing stand ticks, crafter activation and crafter animation ticks, including output/remainder effects. Alpha.26 invalidates reservations before player disconnect, respawn, death, travel and inventory copying. Alpha.27 guards player item use and invalidates reservations before direct player Inventory mutations, while preserving vanilla count-only clear queries. Alpha.28 pauses ongoing player item use and revokes reservations before active release/stop callbacks. Alpha.29 invalidates reservations before equipment, armor/shield and player-attributed durability callbacks and guards player inventory-item ticks. Alpha.30 invalidates reservations before direct loaded block-container mutations and NBT/component reloads, including hopper/furnace overrides. Alpha.31 adds the tested shared explicit write-scope contract without bypassing gameplay hooks. Alpha.32 connects exact one-shot setter authorization with before/after verification for supported physical containers and registered players. Alpha.33 adds a nonblocking common apply driver and worker journal adapter, verified with actual setters and SQLite recovery in isolated live tests. Alpha.34 adds pinned live owner sessions, audited writes and narrow actual chunk/player saves with full saved-image readback checks. Alpha.35 adds the asynchronous save/journal completion protocol with post-commit confirmation and explicit late-commit reconciliation. Alpha.36 adds a bounded shared journal worker with queued-request rejection and observable running-work drain at shutdown. Alpha.37 adds bounded owner-entry retention through worker drain, revoking expired/invalidated permits without permitting early owner reuse. Alpha.38 adds main-thread block API denial during retention and a nonblocking uninstall refusal until drain. Alpha.39 guards direct player slot setters and removals during retention. Alpha.40 guards hotbar rearrangement and bulk calls, including count-only predicates during retention. Next: coordinate insertion with menu cleanup and guard remaining lifecycle/data mutation paths and schedule ordered shutdown, connect trusted exclusive apply/save ports, cover remaining supported mutation paths, verify connected-client behavior, reconcile journal state with verified world/player saves and implement a conservative apply path. The [current preview](ITEM_ROLLBACK.md) never changes items and does not reserve inventories. Rollback of temporary crafting grids and recipe transformations requires a separate recovery design; neither is enabled yet. Extended crafting menus and outputs thrown directly into the world remain separate acceptance/ownership work. Other automation mechanisms remain separate future slices. Fluids and entity logging remain outside this step.

## Step 5 — Environmental attribution

Explosions, TNT, pistons, fire, fluids, falling blocks, growth/decay, sculk, portals, and other non-player transitions.

## Step 6 — Entities and player records

Entity spawn/remove/change, item pickup/drop, sessions, configurable chat/commands, signs and related records.

## Step 7 — Rollback/restore parity

Restore/reapply, cancellation/progress, richer filtering, container/entity rollback, and broader conflict handling.

## Step 8 — CoreProtect database migration/interoperability

Import compatible historical data while keeping Bukkit serialization out of new Fabric-native records.

## Step 9 — Stress, corruption and GameTests

Large queues, interrupted writes, crash recovery, corrupt data, modded registries, and automated GameTests.

## Step 10 — Config/release polish

Advanced Fzzy Config pages, server-only remote credentials, documentation, publishing and upgrade guides.
