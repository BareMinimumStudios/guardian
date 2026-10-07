# Guardian Step 4 inventory and creative validation

Date: 2026-10-07. Current checkpoint: `0.4.0-alpha.3+1.21.1`.

## Automated checks

- Clean build passed with Java 21, Gradle 9.8.0, Fabric Loom 1.17.21, and ModDevGradle 2.0.148.
- All 76 tests passed: 62 platform-neutral tests and 14 Minecraft snapshot/codec tests.
- The standard build reused its configuration cache; the separate WorldEdit adapter build passed.
- New Minecraft tests capture inventory, armor, offhand, and cursor through real inventory-menu classes, check detached item snapshots, reject crafting and invalid indices, and reject extra/replaced menu slots.
- Correlation tests record inventory-to-cursor moves and component-only creative replacements, while unchanged creative replacements produce no transaction.
- SQLite and DuckDB tests persist CREATIVE_SET, query it by name/UUID, upgrade schema 3, and refuse schema 4 through an older migrator. Existing block and container tests remain passing.
- Runtime/source jar contents, release workflow validation, source ZIP integrity, and SHA-256 were checked.

## Dedicated server smoke tests

- Backed up stopped test databases and configuration outside Git before installing the new jars.
- Fabric core plus WorldEdit 7.3.8 and its optional adapter reached `Done` with SQLite schema 4.
- NeoForge reached `Done` with SQLite schema 4 and DuckDB schema 4.
- Status showed a running writer and zero audit/write failures. Player and position lookups completed without errors and returned empty history.
- Servers shut down normally; NeoForge's backend setting was restored to SQLite after DuckDB testing.
- The initial NeoForge saved-world mod-version notice disappeared on the next start. Its standard resource URL notices remain; no Guardian initialization errors appeared.

## Acceptance boundary

No player connected during these checks. Tests exercise snapshots, eligibility, correlation, storage, and migration; idle-server startup does not establish packet-driven gameplay acceptance. Inventory-screen clicks, armor/offhand movement, cursor returns, accepted/rejected creative requests, and protection-mod interactions still require acceptance on both loaders. See [the acceptance guide](STEP_4_TESTING.md).

Inventory-screen capture requires the standard menu layout and an empty crafting area before and after the action. Crafting/result clicks, direct creative drops, automated transfers, unsupported menus, disconnect cleanup, and container rollback remain outside this checkpoint. Fluids and entity logging remain outside Step 4.

## Historical close and standalone action checkpoint

# Guardian Step 4 close and player item validation

Date: 2026-10-07. Current checkpoint: `0.4.0-alpha.2+1.21.1`.

## Automated checks

- Clean build passed with Java 21, Gradle 9.8.0, Fabric Loom 1.17.21, and ModDevGradle 2.0.148.
- All 69 tests passed: 61 platform-neutral tests and 8 Minecraft codec tests.
- The standard build reused its configuration cache. The separate WorldEdit adapter build passed.
- New tests cover close-time cursor return, standalone drops and offhand correlation, no-op actions, ownership validation, nested capture suppression, exception cleanup, and thread ownership.
- SQLite and DuckDB tests persist all new action kinds, query player names without case sensitivity, reopen history by UUID, upgrade schema 2, and reject schema 3 through an older migrator.
- Existing component, block history, atomic-batch, retry, and migration tests remain passing.
- Runtime/source jar contents, release workflow validation, source ZIP integrity, and SHA-256 were checked.

## Dedicated server smoke tests

- Backed up stopped schema-2 test databases outside Git before installing the checkpoint.
- Fabric core plus WorldEdit 7.3.8 and the optional adapter reached `Done` with SQLite schema 3.
- NeoForge reached `Done` with SQLite schema 3, DuckDB schema 3, and a subsequent SQLite restart.
- Status reported a running writer with zero audit/write failures.
- Player-name and block-position lookups returned empty history on both loaders. UUID lookup also passed on NeoForge.
- NeoForge reported the expected saved-world mod-version change on the first upgrade; it disappeared on restart. Its standard resource URL notices remain. No Guardian initialization or mixin errors appeared.
- Servers shut down normally. NeoForge's backend setting was restored to SQLite.

## Acceptance boundary

No player connected during these checks. Startup verifies transformed server classes and database access, but does not establish gameplay acceptance of clicks, close-time cursor returns, Q/Ctrl-Q drops, offhand swaps, or protection-mod cancellation. Follow [the acceptance guide](STEP_4_TESTING.md) on both loaders before release.

Other player inventory-menu/creative packets, automated transfers, unsupported menus, disconnect cleanup, and container rollback remain outside this checkpoint. Fluids and entity logging remain outside Step 4.

## Historical first Step 4 checkpoint

# Guardian Step 4 validation

Date: 2026-10-07. Current checkpoint: `0.4.0-alpha.1+1.21.1`.

## Automated checks

- Clean build passed on Java 21, Gradle 9.8.0, Loom 1.17.21, and ModDevGradle 2.0.148.
- All 58 tests passed: 50 platform-neutral tests and 8 Minecraft codec tests.
- Both loader artifacts compile the same Mojang-mapped item snapshotter and container hook.
- The item codec tests cover name, damage, explicit default-component removal, count-independent payloads, custom-data ordering, empty stacks, corrupt payloads, identity mismatch, and transient-component rejection.
- Correlation tests cover immutability, cancellation, no-op actions, topology changes, component-only changes, and one ID across multiple slot changes.
- SQLite and DuckDB tests cover complete transaction round trips, restart, retry deduplication, location/actor filtering, cursor-only context, atomic rollback of a mixed block/container batch, successful retry, and schema-1 upgrade with an existing block row.
- Repeated standard build reused the configuration cache. Separate WorldEdit adapter build passed.
- Runtime jars, source jars, workflow release validation, ZIP integrity, and SHA-256 were checked.

## Dedicated server smoke tests

- The stopped test databases were backed up outside the repository before migration.
- Fabric core plus the optional WorldEdit adapter reached `Done` using SQLite schema 2.
- NeoForge reached `Done` using SQLite schema 2 and DuckDB schema 2.
- `/guardian status` reported a running writer and zero capture/write failures.
- `/guardian transactions 0 64 0` completed its asynchronous lookup on both loaders and returned an empty history, as expected without connected players.
- The new Fzzy logging setting defaulted to true and was written into configuration version 5. The first upgrade emitted Fzzy's missing-new-field notice; later startup read the updated configuration normally.
- Servers were shut down normally, and SQLite configuration was restored after DuckDB testing.

## Acceptance boundary

No player was connected during these smoke tests. Actual packet-driven clicks, protection-mod cancellation, double-chest addressing, offhand swaps, drag distribution, creative cloning, and modded persistent components still need player acceptance on both loaders. Automated storage and codec tests do not establish those gameplay paths.

This is the first Step 4 slice, not a declaration that all item paths are complete. Close-time cursor returns, standalone inventory/drop packets, automated transfers, crafting/trading/ender-chest/entity inventories, and container rollback remain outside this checkpoint. See [the acceptance guide](STEP_4_TESTING.md).

## Historical Step 3 checkpoint

# Guardian validation

Date: 2026-10-07. Current checkpoint: `0.3.0-alpha.5+1.21.1`.

## Build results

- Java 21.0.10, Gradle wrapper 9.8.0, Fabric Loom 1.17.21, ModDevGradle 2.0.148.
- Fabric, the optional Fabric WorldEdit adapter, and NeoForge all compile using official Mojang mappings.
- `clean build --no-daemon --warning-mode all` passed all 38 tests: 35 platform-neutral tests and 3 Minecraft NBT codec tests.
- The first plain `build` stored its task graph; repeating the identical command reused the configuration cache and passed.
- Separate `:worldedit-adapter:build --no-daemon --warning-mode all` passed.
- Both loader source jars contain the shared Minecraft and domain sources.
- Workflow YAML passed duplicate-key validation. The embedded release script ran locally and checked Fabric core, NeoForge core, and Fabric WorldEdit runtime artifacts, including NeoForge JDBC jar-in-jar metadata.
- The source archive excludes Git internals, build caches, server worlds, logs, databases, and credentials. ZIP integrity and SHA-256 were checked.

## Dedicated Fabric server

- Minecraft 1.21.1, Fabric Loader 0.19.5, Java 21.
- Dependencies: Fabric API 0.116.17, Fabric Language Kotlin 1.14.1, Fzzy Config 0.7.7, Data Attributes 3.0.3, full WorldEdit 7.3.8 production distribution.
- The alpha.5 Mojang-mapped core and optional adapter loaded successfully and reached `Done`.
- SQLite reopened at schema 1 without an unclean-shutdown warning. `/guardian status` reported a running writer and zero failures.
- The server shut down normally. Its SQLite configuration was preserved.

Earlier Guardian baseline checks also verified DuckDB startup on Fabric. WorldEdit's Maven artifact is only a compile dependency; the complete production jar is required at runtime.

## Dedicated NeoForge server

- Minecraft 1.21.1, NeoForge 21.1.256, Java 21.
- Dependencies: Kotlin for Forge 5.12.0, Fzzy Config 0.7.7, Data Attributes 3.0.3.
- The core loaded without a separate common jar. NeoForge discovered both bundled JDBC drivers.
- SQLite startup, normal shutdown, and restart passed. The versioned alpha.5 checkpoint reopened schema 1 without an unclean-shutdown warning.
- DuckDB startup and shutdown passed at schema 1.
- `/guardian status` reported a running writer with zero failures for both backends.
- NeoForge initialized the default permission handler and Guardian's registered nodes.
- The server was stopped normally and its original SQLite configuration restored.

## Acceptance still needed

No player was connected during these smoke tests. Player block placement and breaking, inspector interactions, actual rollback application, and WorldEdit edits still need a player-driven acceptance session. Compilation and idle server startup do not establish those gameplay paths.

The optional WorldEdit adapter currently targets Fabric. Step 4 container and item transactions have not yet been implemented. No fluid or entity logging was added.

Loom still emits notices about OneDrive and the JDBC drivers' four-part Maven versions. These did not prevent build, test, or startup success. No Kotlin return warnings or deprecated WorldEdit constructor warnings remained.

The requested humanize-text pipeline was reviewed but not executed because its LLM and Niutrans credentials are unavailable. Documentation was edited directly and checked against the implemented commands and dependencies.
