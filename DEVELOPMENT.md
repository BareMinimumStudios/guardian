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

5. Implemented: alpha.10 adds bounded, read-only item rollback planning. It checks count/component conservation and current inventories without slot writes. Alpha.11 adds persistent journal entries, exclusive journal claims, guarded transitions and interruption detection. Alpha.12 checks all persisted owner history, recorded block changes and journal claims before preview or preparation. Alpha.13 waits for a committed/flushed accepted audit prefix before preview lookup. Save reconciliation and item rollback apply remain pending. See [item rollback](docs/ITEM_ROLLBACK.md).

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

The current Step 4 checkpoint is version `0.4.0-alpha.13+1.21.1`. It upgrades storage to schema 8 for owner history checks and item rollback tracking and retains GCT2 transient-grid encoding while preserving existing GCT1 item history and block payloads. Standard builds bundle SQLite only; DuckDB is an explicit optional build variant.

Capture uses [MixinExtras WrapMethod](https://github.com/LlamaLad7/MixinExtras/wiki/WrapMethod) and WrapOperation so hooks can chain with other mods. A player-scoped lease suppresses nested actions; the original operation still runs when no capture is possible. Close capture retains the original menu after vanilla resets the active menu.
