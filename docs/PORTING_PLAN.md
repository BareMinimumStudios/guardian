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

Bundle SQLite by default and retain DuckDB as an [optional build-time integration](DISTRIBUTION_SIZE.md). Next: reconcile journal state with durable world/player saves and implement a conservative apply path. The [current preview](ITEM_ROLLBACK.md) never changes items and does not reserve inventories. Rollback of temporary crafting grids and recipe transformations requires a separate recovery design; neither is enabled yet. Extended crafting menus and outputs thrown directly into the world remain separate acceptance/ownership work. Other automation mechanisms remain separate future slices. Fluids and entity logging remain outside this step.

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
