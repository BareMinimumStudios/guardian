# Changelog

All notable changes to Guardian will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Guardian uses semantic versions with a Minecraft version suffix while it is in alpha.

## [Unreleased]

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
