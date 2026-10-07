# Changelog

All notable changes to Guardian will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Guardian uses semantic versions with a Minecraft version suffix while it is in alpha.

## [Unreleased]

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
