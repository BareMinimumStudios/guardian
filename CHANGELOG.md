# Changelog

All notable changes to Guardian will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Guardian uses semantic versions with a Minecraft version suffix while it is in alpha.

## [Unreleased]

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
