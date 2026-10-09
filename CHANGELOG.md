# Changelog

All notable changes to Guardian will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Guardian uses semantic versions with a Minecraft version suffix while it is in alpha.

## [Unreleased]

### Added

- MixinMCP dependency indexing for both loaders and the WorldEdit adapter, with Codex and IntelliJ development guidance.

## [0.4.0-alpha.48+1.21.1] - 2026-10-09

### Added

- Complete immutable before/expected-after inventory receipts, preserving unchanged and empty slots and item components.
- Read-only saved-state reconciliation with guarded atomic acknowledgment, persistent decision receipts and idempotent recovery after a lost reply.
- Internal Minecraft read-only adapter for complete actual saved container/player data, without forcing new saves.
- Regression coverage for corruption, bounds, stale evidence, partial saves, late decisions, restart, callback stops and full player armor/offhand snapshots.

Database schema is now 10. Legacy marker-only records remain protected without fabricated images. Production admission, lifecycle integration and live acceptance remain pending; item rollback apply stays disabled.

## [0.4.0-alpha.47+1.21.1] - 2026-10-09

### Added

- Durable item-operation protection registered against an exact prepared journal and its owner/source claims before item writes.
- Protected completed operations remain visible in recovery listings and retain owner claims after restart. Cancellation cannot clear protected intent.
- Bounded worker requests and tests for SQLite, the optional DuckDB backend, schema upgrades, late acknowledgments and protected host shutdown.

Database schema is now 9. Protection has no automatic release or replay path. Normal-server admission does not yet register protection; item rollback apply remains disabled.

## [0.4.0-alpha.46+1.21.1] - 2026-10-09

### Added

- Internal ordered shutdown for a bounded group of item rollback hosts: stop admission, drain started journal work, then close its database off the server thread.
- Tests for callback admission, late commits, observer cancellation, pending saves and actual SQLite close/reopen recovery.

Unresolved operations keep their in-process owner protection after database closure. Persistent reconciliation and normal-server lifecycle integration remain pending; item rollback apply stays disabled.

## [0.4.0-alpha.45+1.21.1] - 2026-10-09

### Added

- Internal operation host connecting journal intent, audited inventory writes, complete saved-owner verification and confirmed completion.
- Explicit owner protection through persistent-outcome reconciliation, including late commits after cancellation.
- Regression and live-server checks for complete handoff, partial writes and cancellation during an actual SQLite commit.

Item rollback apply remains disabled pending persistent reconciliation, verified live admission/scheduling and real-client acceptance.

## [0.4.0-alpha.44+1.21.1] - 2026-10-09

### Fixed

- Curse of Vanishing removes cursed items during retained death cleanup instead of letting the ordinary removal guard turn them into drops.
- Inventory binding now refuses dead, disconnected and dimension-changing players until their lifecycle state settles.

### Added

- Combined checks for normal death drops, keep-inventory death, vanishing items and same-level/Nether travel on both loaders.

Item rollback apply remains disabled pending production integration, real-client acceptance and persistent outcome reconciliation.

## [0.4.0-alpha.43+1.21.1] - 2026-10-09

### Fixed

- Respawn preserves the complete player inventory while journal protection is retained. Only the exact vanilla copy receives temporary setter authorization; ordinary writes stay blocked and old rollback permits stay revoked.

### Added

- Live checks for retained respawn and disconnect on both loaders, including all 41 inventory slots, item components and actual player-file contents.

Item rollback apply remains disabled pending remaining lifecycle acceptance, exclusive production integration and persistent outcome reconciliation.

## [0.4.0-alpha.42+1.21.1] - 2026-10-09

### Fixed

- Cleanup during retained journal work now revokes all active operations before menu-owner resolution, preserving pending owner protection through worker drain.

### Added

- Regression checks for normal cursor/crafting item returns during retained cleanup, including audit capture with logging enabled.

Item rollback apply remains disabled pending lifecycle acceptance, exclusive production integration and persistent outcome reconciliation.

## [0.4.0-alpha.41+1.21.1] - 2026-10-09

### Added

- Player inventory binding and audited writes refuse active menus, cursor items, crafting inputs and result items without moving them.
- Bound player sessions permanently reject changed temporary ownership and invalidate their original operation, including pending saved-image completion.

Item rollback apply remains disabled pending coordinated cleanup and the remaining exclusion/integration work.

## [0.4.0-alpha.40+1.21.1] - 2026-10-09

### Added

- Hotbar selection and swaps are refused while journal protection is pending, preserving stacks, components and the selected slot.
- Bulk inventory operations return zero before predicates, extra-container access or cursor changes during pending protection, including count-only calls.

Item rollback apply remains disabled. Insertion refusal still needs coordinated menu cleanup to avoid losing returned items.

## [0.4.0-alpha.39+1.21.1] - 2026-10-09

### Added

- Direct player inventory slot writes and removals are refused while journal protection is pending, without changing the offered stack or inventory.
- Audited player setter authorization remains available during protection; ordinary operations resume after actual journal drain.

Item rollback apply remains disabled pending composite operations, lifecycle handling and trusted production integration.

## [0.4.0-alpha.38+1.21.1] - 2026-10-09

### Added

- Main-thread block-inventory API denial while journal protection is retained, with empty removal results and cancelled void writes.
- Exact audited setter chains remain available without allowing ordinary writes to borrow their authorization.
- Nonblocking uninstall checks that preserve the coordination binding and sessions until journal protection drains.
- Related transfer/menu entry points and unrelated furnace ticks pause during retention to avoid partial moves around blocked block APIs.

Item rollback apply remains disabled pending the remaining mutation/lifecycle guards and trusted production integration.

## [0.4.0-alpha.37+1.21.1] - 2026-10-08

### Added

- Owner reservations retained until their journal worker drains, including after expiry, invalidation, close or stop.
- Revoked retained leases deny write permits and prevent overlapping acquisition until settled journal results are observed.
- SQLite checks for delayed commits and lost acknowledgements while expired owner entries remain reserved.

Retention does not establish physical mutation exclusion. Item rollback apply remains disabled pending live guards and ordered binding shutdown.

## [0.4.0-alpha.36+1.21.1] - 2026-10-08

### Added

- Shared bounded journal worker for asynchronous apply and save-completion protocols.
- Shutdown that rejects new and queued work while retaining actual results from running commits.
- Observable drain before backend disposal, with SQLite restart checks for queued commits, completed commits and lost acknowledgements.

Item rollback apply remains disabled pending mutation exclusion through commit drain and production inventory coordination.

## [0.4.0-alpha.35+1.21.1] - 2026-10-08

### Added

- Asynchronous save-completion protocol with inventory calls on the driver thread and journal work on a separate worker.
- Full journal checks around owner saves and completion, plus a fresh completed-record read before reporting success.
- Explicit reconciliation of stopped or timed-out completion attempts, without cancelling or retrying submitted database writes.

Item rollback apply remains disabled pending complete mutation exclusion and production orchestration.

## [0.4.0-alpha.34+1.21.1] - 2026-10-08

### Added

- Bounded live inventory sessions that pin registered players, physical containers, loaded chunks, block states and complete logical slot layouts.
- Narrow chunk/player saves followed by actual disk readback, full inventory/component comparison and stale identity/lease checks.
- One session per lease, shared bounded player-file reading and cleanup of pending results when sessions or server coordination close.

Sessions verify identities and saved inventory images; they do not certify complete mutation exclusion or mark rollback journals complete. Item rollback apply remains disabled.

## [0.4.0-alpha.33+1.21.1] - 2026-10-08

### Added

- An internal journal-confirmed apply driver that checks full inventory images, waits for committed APPLYING intent and reads it back before calling audited slot setters.
- An asynchronous journal adapter that refuses live-thread I/O and inline/rejected worker execution, with twenty-one new driver and adapter contract tests.
- Recovery-preserving handling of partial writes, setter side effects, lost ownership, journal changes, timeouts and late results after stopping.

Written items are not marked completed. Verified saves, remaining mutation exclusion and a production Minecraft apply port are still required; item rollback apply remains disabled.

## [0.4.0-alpha.32+1.21.1] - 2026-10-08

### Added

- Reserved single-slot writes for current vanilla player inventories and loaded barrels, chests, hoppers, dispensers, droppers, furnaces, blast furnaces and smokers.
- Exact container/slot/stack authorization that consumes the audited setter entry sequence before setter side effects, with immutable before/after checks and data-component-aware restore.
- Conservative refusal of stale identities, unsupported inventories, wrong owners, deferred loot, invalid slots, oversized stacks and off-thread writes.

The primitive remains internal to the unfinished apply path. Item rollback apply stays disabled until remaining mutation exclusion, journaling and saved-state completion are verified.

## [0.4.0-alpha.31+1.21.1] - 2026-10-08

### Added

- Explicit single-owner write scopes backed by a current reservation, with thread checks, callback-lifetime permits and final lease validation.
- Failure cleanup that revokes the original operation without affecting a replacement lease, plus eleven contract tests for stale, escaped, deferred and nested authorization.

This shared-core contract is not connected to Minecraft setters or the save driver. No gameplay guard is bypassed and item rollback apply remains disabled.

## [0.4.0-alpha.30+1.21.1] - 2026-10-08

### Added

- Reservation invalidation before direct block-container slot writes, removals, clearing, loot-table/seed setters and hopper/furnace overrides.
- Guards before loaded block-container NBT reload and Data Component application entry points.

### Changed

- Empty reservation registries skip expiration clock reads and scans on frequent guard calls.

Direct changes proceed normally after cancellation. Detached/off-thread containers, direct mutable lists/stacks, arbitrary overriding methods and verified save/apply remain pending; item rollback apply stays disabled.

## [0.4.0-alpha.29+1.21.1] - 2026-10-08

### Added

- Reservation invalidation before player equipment changes, armor/shield callbacks and player-attributed durability changes, including NeoForge's extra damage overload.
- Player inventory-item tick guards before stack animation updates and item callbacks during reservations.

Equipment changes and combat continue normally after invalidation. Arbitrary mutable stack/list writes, unattributed damage and verified save/apply remain pending; item rollback apply stays disabled.

## [0.4.0-alpha.28+1.21.1] - 2026-10-08

### Added

- Guards for ongoing player item use: start, progress, direct use ticks and completion pause during reservations.
- Reservation invalidation before active release/stop callbacks, allowing ordinary cleanup to proceed on both loaders.

These hooks cover standard LivingEntity use paths for server players. Mutable stack/list writes, arbitrary modded callbacks and verified save/apply remain pending; item rollback apply stays disabled.

## [0.4.0-alpha.27+1.21.1] - 2026-10-08

### Added

- Player item-use and block-use guards before vanilla/NeoForge callbacks, with authoritative inventory resynchronization on refusal.
- Reservation invalidation before direct player inventory setters, insertion/removal, loading, copying, clearing, returning items and hotbar picking.
- Conservative invalidation for modifying bulk clears; count-only queries retain reservations.

Direct inventory changes still proceed normally. Existing uses, direct mutable stack/list writes and the trusted save/apply permit remain separate work; item rollback apply stays disabled.

## [0.4.0-alpha.26+1.21.1] - 2026-10-08

### Added

- Whole-operation reservation invalidation before player disconnect saving, respawn, death, dimension transition and inventory copying on both loaders.
- Cleanup of known participating menu owners, with conservative invalidation when a transitioning player's menu cannot be resolved.

Lifecycle actions still proceed normally. This does not add player/entity audit capture or enable item rollback apply.

## [0.4.0-alpha.25+1.21.1] - 2026-10-08

### Added

- Brewing stand tick guards before fuel, potion, ingredient and timer changes or brewing hooks run.
- Crafter activation guards before recipe assembly, ingredient consumption, output/remainder insertion or ejection, and tick guards before animation timers change.
- Shared automation gating on both loaders, independent of transaction logging.

Brewing and crafter automation pause while any inventory reservation exists. No audit capture or rollback of their recipe transformations is added; item apply remains disabled.

## [0.4.0-alpha.24+1.21.1] - 2026-10-08

### Added

- Dispenser and dropper guards on both loaders, before random slot selection, item reads or behavior/capability callbacks.
- Conservative pause of dispensing during any inventory reservation because custom behaviors and NeoForge handlers can affect owners beyond the visible target.
- Regression coverage for player and block reservations, multiple operations, expiry, shutdown and thread confinement.

Ordinary dispensing is unchanged without reservations. Commands do not acquire reservations yet, and item rollback apply remains disabled.

## [0.4.0-alpha.23+1.21.1] - 2026-10-08

### Added

- Reservation guards that pause vanilla furnace, blast furnace and smoker ticks before fuel, cooking progress or slots change.
- Whole-operation invalidation before loaded container block-state changes, block-entity installation/removal and server chunk unloading, including connected chest halves across chunk boundaries.
- Chunk invalidation regression tests for whole-operation scope, negative coordinates, dimensions, stale leases, expiry, shutdown and thread confinement.

This adds coordination safeguards, not smelting audit capture. Other automation, unsupported modded mutation paths and verified completion saves remain pending; item apply remains disabled.

## [0.4.0-alpha.22+1.21.1] - 2026-10-08

### Added

- Server-side reservation guards for menu click packets, recipe placement, player drops/offhand swaps and creative inventory packets, independent of item audit logging.
- Authoritative inventory resynchronization when reserved clicks are refused, without accepting client-predicted slot contents.
- Pre-creation menu opening guards, including NeoForge's extended opening API, and reservation invalidation before close-time item returns.
- Bounded resolution of verified vanilla menus and physical inventory owners; unknown menus/providers refuse access during active reservations and unknown cleanup invalidates remaining operations.

Commands do not acquire reservations yet. Item rollback apply remains disabled.

## [0.4.0-alpha.21+1.21.1] - 2026-10-08

### Added

- Menu coordination policy for participating player and container owners, with bounded resolution and conservative refusal of unknown menus during reservations.
- Cleanup invalidation that releases affected operations before carried items are returned; unknown cleanup invalidates all remaining reservations.
- Regression coverage for menu access, cleanup, expiry, shutdown and thread confinement.

This is common policy infrastructure. Minecraft menu hooks and item apply remain pending.

## [0.4.0-alpha.20+1.21.1] - 2026-10-08

### Added

- Hopper push/pull reservation guards on Fabric and NeoForge, independent of audit logging and aware of physical chest halves across chunk boundaries.
- Server-owned coordination lifecycle and transfer-gate regression tests.

The guards are verified with active test reservations. Commands do not acquire reservations yet; item apply remains disabled.

## [0.4.0-alpha.19+1.21.1] - 2026-10-08

### Added

- Bounded inventory-owner reservations, operation-scoped mutation permits and whole-operation invalidation for future coordinated item rollback.
- Regression coverage for overlapping owners, stale permits, timeout, thread confinement and save-completion interruption.

Item apply remains disabled. Reservations have no gameplay hooks yet and do not freeze inventories.

## [0.4.0-alpha.18+1.21.1] - 2026-10-08

### Added

- Saved-player main, armor and offhand slot comparisons through the existing read-only recovery command.
- Bounded background player-file reads, exact UUID/version checks and strict saved-to-logical slot mapping.
- Regression coverage for components, file budgets, queue saturation, missing/corrupt data and reader shutdown.

### Fixed

- Late saved-player read completion cannot report success after the reader stops.


## [0.4.0-alpha.17+1.21.1] - 2026-10-08

### Added

- Read-only `/guardian rollback-items recovery saved <operation UUID>` comparison for supported saved vanilla block-container slots.
- Region reads on the owning Minecraft I/O worker after flushing queued writes, bypassing the pending-write read cache.
- Bounded saved-chunk NBT decoding with exact item components, physical slot layouts, position/version checks and sealed-loot refusal.

### Changed

- Saved checks preserve live items, journal phases and claims. They do not serialize current live chunks or enable rollback apply.


## [0.4.0-alpha.16+1.21.1] - 2026-10-08

### Added

- Platform-neutral saved-state completion protocol that requires all participating owner readbacks before a guarded journal completion.
- Fault tests for partial save failures, missing or conflicting readbacks, ownership loss, changed journals, late callbacks and timeouts.
- SQLite restart checks showing that failed completion retains claims and requires recovery, while verified completion retains source claims.

### Changed

- Documented the Minecraft save adapter requirements. The protocol is not connected to commands or live saves; item rollback apply remains disabled.


## [0.4.0-alpha.15+1.21.1] - 2026-10-08

### Added

- Bounded audit-owner watches for item preview and recovery observations.
- Invalidation when captured item or block changes touch a participating owner, including rejected queue submissions and temporary player cursor/crafting activity.
- Regression coverage for concurrent submissions, change-and-return sequences, isolated owners, capacity bounds and lifecycle cleanup.

### Changed

- Captured activity during a multi-tick item check makes the affected owner unavailable, even if its items later return to the same state.
- Watches are released on completion, refusal, timeout and shutdown. They retain owner identities only, with no item snapshots or audit history.


## [0.4.0-alpha.14+1.21.1] - 2026-10-08

### Added

- Read-only item recovery listing and per-operation observations, with suggested commands and operation UUID completion.
- Bounded recovery validation for coherent transfer chains, exact item components, unavailable owners and ambiguous results.
- Live container and player identity checks before returning an item observation.

### Changed

- Item previews repeat the accepted audit barrier and persisted-history checks after reading inventories.
- Preview and recovery checks share one active slot, read only participating item slots and bound retained item payloads to 16 MiB.
- Item rollback apply remains disabled; recovery observations preserve journal phases and claims.


## [0.4.0-alpha.13+1.21.1] - 2026-10-08

### Added

- Asynchronous accepted-prefix audit barriers, acknowledged only after committed batches and a successful storage flush.
- Bounded pending requests, cancellation and explicit failures during startup, shutdown or flush errors.
- Regression coverage for delayed writes/flushes, retries, receipt ownership, concurrent submitters, callbacks and lifecycle failures.

### Changed

- Item rollback previews wait for the accepted audit prefix before lookup and owner-history checks. A ten-second preparation timeout releases its pending barrier.
- Audit submission and shutdown share a short ordering gate; storage I/O and completion callbacks stay outside it.


## [0.4.0-alpha.12+1.21.1] - 2026-10-08

### Added

- Schema 8 logical inventory owner index, written atomically with item transactions and backfilled from existing history in bounded pages.
- Preview checks for excluded newer or equally timed transactions, recorded block changes at a container position, unfinished inventory reservations and claimed source records.
- The same persisted-history checks inside atomic journal preparation, independent of user and region filters.
- Migration, hidden-history, player cursor/grid, source retry, block history and journal-claim regressions on SQLite and the optional DuckDB backend.

### Changed

- Item previews now refuse backends that cannot verify persistent history. Item rollback apply remains disabled.


## [0.4.0-alpha.11+1.21.1] - 2026-10-08

### Added

- Schema 7 item rollback journal with atomic operation entries, persistent inventory reservations and source transaction claims.
- Guarded phase transitions, idempotent preparation, source-payload verification and bounded recovery headers.
- Startup detection of interrupted apply intents without automatic item replay, plus unfinished journal counts in status output.
- Component-aware recovery observations for original, restored, partial, conflicting, unavailable and indistinguishable inventories.
- Restart, overlapping-plan, cancellation, duplicate-source and actual child-process abrupt-stop regressions on SQLite and the optional DuckDB backend.


## [0.4.0-alpha.10+1.21.1] - 2026-10-08

### Added

- Read-only container rollback preview with explicit time and region filters, permissions and tab completion.
- Component/count conservation checks, checks on both transfer endpoints, and reverse-history simulation without changing live items.
- Conservative skips for changed inventories, unavailable owners, temporary slots, unsupported actions, uncertain ordering and dependent older transactions.
- Bounded inventory reads and history limits, with planner regressions and dedicated-server preview checks on both loaders.


## [0.4.0-alpha.9+1.21.1] - 2026-10-08

### Added

- Correlated vanilla inventory and crafting-table transactions, including recipe-book ingredient placement, accepted result takes, bulk crafting, ingredient returns on close, and recipe leftovers.
- Player-owned temporary crafting-grid addresses, distinct from persistent containers and derived recipe previews.
- Crafting summaries that show items gained and ingredients used, with crafting-table coordinates where available.
- Schema 6 and bounded GCT2 slot encoding, retaining reads of existing GCT1 item history and preventing older builds from opening upgraded databases.
- Crafting ownership, component immutability, layout rejection, migration, persistence and presentation regressions; live crafting probes on both loaders.

### Changed

- Vanilla inventory actions are captured with populated crafting grids. Extended menus are still skipped rather than partially recorded.

## [0.4.0-alpha.8+1.21.1] - 2026-10-07

### Added

- Optional `-PbundleDuckDb=true` self-contained build variants with distinct artifact names.
- Clear missing-driver errors before creating database files or locks, with regression coverage for preserving existing data.
- Startup/class-loading and memory observations for both loaders, plus live sealed-loot acceptance.

### Changed

- Standard Fabric and NeoForge jars bundle SQLite only, reducing core downloads to about 12 MiB. DuckDB remains a tested optional integration.
- Default release checks reject DuckDB bundles and core jars over 16 MiB.
- Database settings explain which backend is bundled and that switching backends does not convert existing history.

## [0.4.0-alpha.7+1.21.1] - 2026-10-07

### Added

- Controlled 10/50/100-hopper load results for both loaders, plus live furnace sided-slot and loaded chunk-boundary acceptance.

### Fixed

- Inventory-only actions recorded while a container is open no longer claim that items changed in its other half. The history names the affected inventory and states when the inspected container was unchanged.
- Inspecting an unchanged half of a double chest shows the actual item changes and coordinates of the affected half.

### Changed

- Hopper routes explicitly label their source with `from` and their destination with `to`.
- Reuse immutable default-item payloads within each block-inventory snapshot to reduce repeated encoding under hopper load. Modified component patches retain full codec validation; the cache never survives an inventory read.


## [0.4.0-alpha.6+1.21.1] - 2026-10-07

### Added

- Clickable Previous/Next and ordering controls for filtered block/item history and inspector pages.
- `o:oldest` / `order:oldest` and `o:newest` ordering across SQLite, DuckDB and in-memory storage.
- Server-thread packet interception for permission-controlled read-only inspection before normal claim interaction callbacks. Inspection respects block reach and does not load target chunks.

### Changed

- Inspector pages show at most five transactions, with navigation for older records.
- Hopper history shows a single source-to-destination route instead of independent generic container deposits/removals.
- Recheck inspection permission on clicks and page navigation; acknowledge canceled packet sequences and resynchronize predicted blocks/items.

The supplied audit database confirms the reported furnace entered the hopper at 1710,85,3872 and did not enter the full barrel at 1710,84,3872. No false barrel transaction was found. The presentation obscured the transfer endpoints.

## [0.4.0-alpha.5+1.21.1] - 2026-10-07

### Added

- Filter completion for online player names, time units, actions, radius and result limits.
- `transactions u:<player>` / `user:<player>` with time, radius, coordinates, WorldEdit selection and result limits. Existing coordinate and `player` forms remain supported.
- `p:` / `page:` pagination for older matching block and item records.
- Right-click container inspection for item history; left-click keeps block history.
- Accepted door, trapdoor, gate, lever and button state changes in block history.

### Fixed

- Guardian usage examples now use `/guardian`.
- Replace raw transaction IDs and cursor/slot dumps with colored, relative-time additions and removals, including clear hopper attribution.
- Resolve online player names to UUIDs for filtered lookups; report the result cap and dimension scope explicitly.
- Replace the corrupted lookup progress ellipsis with plain text.
- Balance placement capture frames on early returns and exceptions from modded placement callbacks.

Opening a container without moving items is not an item transaction. Missing past interaction events cannot be reconstructed. Hopper recording remains opt-in.

## [0.4.0-alpha.4+1.21.1] - 2026-10-07

### Added

- Opt-in block-to-block hopper push and pull logging on Fabric and NeoForge, with both inventories in one transaction.
- System attribution through `minecraft:hopper`, separate from player history.
- Item identity and count conservation checks, including persistent Data Components, before accepting a transfer record.
- `logging.automatedContainerTransfers` and hopper submission/failure/backpressure counters.
- Tests for balanced transfers, failed/no-op attempts, topology changes, component mismatches, system queries, retry deduplication, and schema-4 upgrade.

### Changed

- Upgrade storage to schema 5 for system item actors and the new action kind. Existing block and item payload formats remain unchanged.
- Retain both double-chest positions in hopper transaction context.
- Skip sealed loot containers, unloaded neighbours, and unsupported inventories. Snapshot capture does not generate loot or load additional chunks.

Hopper logging defaults to false while broader load testing remains pending. This slice observes block-container inventories around the full transfer attempt, including NeoForge's capability fast path. Entity inventories, loose item pickup, unrelated capability storage, crafting, and container rollback remain outside this checkpoint.

## [0.4.0-alpha.3+1.21.1] - 2026-10-07

### Added

- Player inventory-screen clicks and cursor returns when the crafting area is empty, including armor and offhand slots.
- `CREATIVE_SET` transactions around accepted server-side writes to player inventory slots, including component-only replacements.
- Tests using Minecraft inventory-menu classes for logical slot mapping, immutable captures, crafting exclusions, and creative changes.
- Schema-3 upgrade and creative-action persistence checks on SQLite and DuckDB.

### Changed

- Share player inventory and cursor snapshot code across block menus, inventory screens, and standalone actions.
- Skip crafting/result clicks, occupied crafting areas, and extended or replaced inventory-menu layouts rather than persist incomplete transactions.
- Upgrade databases to schema 4 to guard the new creative action name against older readers. Payload formats and block history remain unchanged.

Creative packets that only spawn a dropped item are outside this checkpoint. Player-driven gameplay acceptance, crafting correlation, automated transfers, and container rollback remain pending.

## [0.4.0-alpha.2+1.21.1] - 2026-10-07

### Added

- Correlated close-time cursor returns from supported block-container menus.
- Standalone Q/Ctrl-Q drops and offhand swaps, with player inventory lookup through `/guardian transactions player <name-or-uuid>`.
- A per-player action scope that prevents nested close/drop hooks from recording an action twice and releases safely on exceptions.
- Tests for close/drop/swap correlation, scope cleanup, player identity, actor-name queries, and schema-2 upgrades on SQLite and DuckDB.

### Changed

- Chain menu capture with other mods through MixinExtras wrappers.
- Require changed-slot owners to match the transaction's player or declared container context.
- Upgrade storage to schema 3 so older readers reject the new action kinds safely. Existing block and item payloads remain unchanged.

Gameplay acceptance is still pending on both loaders. Automated transfers, other player-inventory menu/creative packets, unsupported menus, and container rollback remain outside this checkpoint.

## [0.4.0-alpha.1+1.21.1] - 2026-10-07

### Added

- Immutable item snapshots using Minecraft's registry-aware item codec, including persistent Data Components and explicit default-component removals.
- One transaction ID for all changed block-container slots, player inventory slots, and cursor contents from an accepted menu click.
- Shared Fabric and NeoForge capture hooks for block-backed container menus, including the two halves of a double chest.
- Atomic SQLite and DuckDB transaction persistence, retry deduplication, and location-based queries.
- `/guardian transactions <x> <y> <z>` using the existing lookup permission.
- A live `logging.containerTransactions` switch and container capture counters in `/guardian status`.
- Tests for component round trips, cancelled/no-op correlation, immutable snapshots, mixed-batch rollback, retries, and migration from schema 1.

### Changed

- Migrated databases to schema 2 while preserving existing block history and payload formats.
- Bounded item and transaction encodings; unsupported transient component patches are reported as capture failures.

This first Step 4 slice captures accepted clicks in supported block-container menus. It does not yet log close-time cursor returns, automated inventory transfers, ender chests, entity inventories, crafting menus, or standalone inventory/drop packets. Container rollback is not enabled. Player-driven acceptance remains required on both loaders.

## [0.3.0-alpha.5+1.21.1] - 2026-10-07

### Added

- A dedicated-server NeoForge 1.21.1 artifact with lifecycle hooks, inspector events, block capture, and NeoForge permission nodes.
- A flat Guardian banner using the approved shield and wordmark style.
- NeoForge release artifacts in the mc-publish workflow.

### Changed

- Switched Fabric and the optional WorldEdit adapter from Yarn to official Mojang mappings.
- Moved Minecraft adapters, configuration, commands, and rollback code into a source directory compiled by both loaders.
- Bundled the shared core and JDBC drivers in each loader artifact while keeping Fzzy Config external.
- Kept Gradle 9.8.0 and Fabric Loom 1.17.21; added ModDevGradle 2.0.148 for NeoForge.

## [0.3.0-alpha.4+1.21.1] - 2026-10-07

### Changed

- Extracted the immutable block domain, SQLite/DuckDB persistence, buffered writer, bulk audit queue, command filter parsing, and rollback reconciliation into a loader-independent `common` module.
- Moved platform-neutral tests into `common` while keeping Minecraft NBT tests with the Fabric platform.
- Bundled the shared core directly into the Fabric runtime jar, so server installation still uses one core mod jar.

## [0.3.0-alpha.3+1.21.1] - 2026-10-07

### Added

- A lightweight 256×256 Guardian shield and rollback icon.
- Release publishing through mc-publish with separate destination selection.
- Development notes for the Fabric baseline and planned NeoForge migration.

### Changed

- Renamed ExProtect to Guardian, including mod IDs, packages, commands, permission nodes, configuration, and artifact names.
- Marked the Fabric core as server-only.
- Reworked the README around installation, commands, and current support.

### Fixed

- DuckDB lookup failures caused by unsupported label-based BLOB reads; reads now use column indices.
- Kotlin expression-body return warnings in storage append and shutdown.
- Deprecated WorldEdit exception constructors.

The imported Step 3 Fix 1 prototype was version `0.3.0-alpha.2+1.21.1`. Its original release date is not recorded here. Earlier staged work is described in `docs/PORTING_PLAN.md`.
