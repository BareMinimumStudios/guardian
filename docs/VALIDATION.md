# Guardian item rollback preview checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.10+1.21.1`.

- All 135 tests passed: 106 common and 29 Minecraft tests. Clean build, repeated configuration-cache reuse build and separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0. Loom still reports its known four-part SQLite JDBC version metadata warning; there are no compiler or test failures.
- Dedicated Fabric and NeoForge fixtures each produced 56 unique hopper records in an isolated test database. A two-record transfer chain preview returned two eligible transactions without changing inventory contents.
- Both loaders skipped an endpoint outside the selected region, rejected a changed destination and its dependent older transaction, preserved sealed loot, and refused a selection above the 50-record cap. Before/after inventory reads matched in the read-only cases.
- Restarting with the original database retrieved existing three-record and two-record hopper histories. Independent hashes of all stored block and item rows were unchanged.
- Final SQLite-only alpha.10 jars are installed on both dedicated servers. They are stopped, with original configurations restored and fixtures removed. Test databases, logs and prior jars are archived outside Git. Production files were untouched.
- Schema 6, config version 6 and snapshot formats are unchanged. See [the validation data](validation/item-preview-alpha10.json) for limits and artifact hashes.

This milestone is preview-only. It observes inventories over several ticks and does not reserve them. Item rollback apply, durable journaling, interruption recovery and player-driven recovery acceptance remain pending. See [item rollback](ITEM_ROLLBACK.md). Existing crafting client/modpack acceptance checks also remain open.

## Historical alpha.9 crafting checkpoint

# Guardian crafting correlation checkpoint

Date: 2026-10-08. Checkpoint: `0.4.0-alpha.9+1.21.1`.

- 118 tests passed: 89 common and 29 Minecraft tests. The clean build, subsequent configuration-cache reuse build and separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0.
- Live synthetic-player probes on Fabric and NeoForge invoked the accepted click packet handler and produced eight unique records each: five CRAFT, one RECIPE_PLACE and two CLOSE. Both reported zero capture failures and backpressure.
- Independent decoding verified consumed ingredients, actual player/cursor gains, twelve-plank bulk output, cake's three empty-bucket leftovers, conservation in noncraft actions and three records indexed at the real crafting-table location.
- Regression coverage includes immutable custom components, distinct player/menu grid ownership, preview exclusion, exact-layout rejection, no-op/canceled correlation, schema-5 migration, older-reader rejection, persistence/restart on both JDBC backends and concise crafting presentation.
- Schema 6 adds guarded crafting ownership and action support. GCT2 encodes grid changes; existing GCT1 records remain readable. Block payloads and config version 6 are unchanged.
- Both dedicated servers have the final SQLite-only alpha.9 jars installed and are stopped, with original configurations restored. Temporary harness mods/databases were removed or archived outside Git, and the table fixture was removed. Production files and the supplied production database were untouched.

Actual-client tests remain for recipe clicks, full inventories, protection cancellation, modded recipes and prediction on both loaders. Unknown crafting layouts and thrown result outputs remain outside this bounded slice. Container rollback is next; temporary grids and recipe transformations require separate recovery rules. See [the crafting test guide](STEP_4_TESTING.md#crafting-acceptance-on-each-loader).

## Historical alpha.8 SQLite checkpoint

# Guardian SQLite distribution checkpoint

Date: 2026-10-07. Checkpoint: `0.4.0-alpha.8+1.21.1`.

- Standard Fabric and NeoForge jars bundle SQLite only and are approximately 11.85 MiB each, down from approximately 88 MiB. Release validation rejects extra bundled drivers or a core jar above 16 MiB.
- All 104 tests pass: 82 common and 22 Minecraft tests. Two new regressions verify missing-driver failure before directory creation and preservation of an existing database.
- Both default builds started on the dedicated servers and retrieved their existing item history. No DuckDB classes loaded. One diagnostic startup per loader measured 10.523 seconds for Fabric and 11.591 seconds for NeoForge; these are observations, not benchmark guarantees.
- Both optional `-PbundleDuckDb=true` builds started with DuckDB selected. Standard builds with DuckDB selected produced the explicit missing-driver error and left existing database files unchanged. No automatic database conversion or fallback occurs.
- The clean build, configuration-cache reuse build and separate WorldEdit adapter build passed with Java 21 and Gradle 9.8.0.
- Both dedicated servers retain the default alpha.8 SQLite jars, original SQLite configurations and disabled automated hopper logging, and are stopped. Backups and optional artifacts remain outside Git. Production files were not changed.

Schema 5, config version 6 and snapshot formats remain unchanged. Logging continues through the bounded background batch writer. See [distribution options](DISTRIBUTION_SIZE.md) and [historical startup measurements](STARTUP_VALIDATION.md).

Sealed-source and sealed-destination hopper fixtures also passed on both loaders during this milestone: 12 unique balanced records each, expected conservative first-transfer skips and correct final counts. Next is bounded crafting correlation, followed by conservative container rollback. Connected-client NeoForge protection checks and longer component-heavy load runs remain open; entity and fluid logging remain outside Step 4.

## Historical alpha.7 hopper reliability checkpoint

# Guardian hopper reliability checkpoint

Date: 2026-10-07. Checkpoint: `0.4.0-alpha.7+1.21.1`.

- 102 tests passed: 80 common and 22 Minecraft tests. The clean build passed, the second build reused its configuration cache, and the separate WorldEdit adapter build passed with Gradle 9.8.0 and Java 21.
- Regression tests cover unchanged-container presentation, actual changes in another physical chest half, explicit hopper endpoints, default-payload reuse with independent counts, patched components and transient-component rejection.
- Controlled logging-off/on runs exercised 10, 50 and 100 hoppers on Fabric and NeoForge. No capture/write failures or backpressure occurred. Independent decoding after shutdown confirmed every accepted record persisted, with exact item/component conservation and unique transaction IDs.
- Separate live fixtures passed furnace input/fuel/output slot checks, rejected side fuel insertion and a loaded chunk-boundary transfer. Each loader produced the expected 27 balanced records.
- The user reports that inspector paging, claim-denied inspection and permission revocation work on the supplied pack server. Screenshots confirmed the permission-removal message and the full-barrel rig recorded the pull into the hopper without a barrel insertion. These are user acceptance results; they do not establish the equivalent connected-client behavior on NeoForge.
- The single-barrel “other half” message was a formatting error for inventory-only actions associated with an open menu. The formatter now identifies the affected inventory and states that the inspected container was unchanged.
- Both dedicated servers have alpha.7 installed, are stopped, and have automated hopper logging restored to false. Prior databases, configuration and jars are backed up outside Git. No production server files or supplied production database were changed.

See [the hopper load report](HOPPER_PRESSURE_TEST.md) for sample ranges, fixture details and limits. The optimization is scoped to one inventory read and unmodified component patches; no mutable item stack enters stored history and no long-lived cache was introduced. Schema 5, config version 6 and item payload formats are unchanged.

Remaining staged work includes crafting correlation and conservative container rollback. Sealed-loot runtime acceptance, unloaded-neighbor cases, component-heavy sustained load, longer memory observation and connected-client NeoForge protection/prediction tests remain open. Fluids and entity logging remain outside Step 4.

## Historical alpha.6 validation

# Guardian history presentation and inspector validation

Date: 2026-10-07. Checkpoint: `0.4.0-alpha.6+1.21.1`.

- 97 tests passed: 80 common and 17 Minecraft tests. Clean build passed; the subsequent standard build reused its configuration cache. Java 21, Gradle 9.8.0 and both loader mappings remain unchanged.
- Regression checks cover filter-preserving clickable commands, order validation, chronological block/item pagination on SQLite and DuckDB, navigation click payloads and availability, and visible hopper endpoints.
- Read the supplied production database in immutable read-only mode. The furnace transaction removes one item from source chest 1710,86,3872 and inserts it into hopper 1710,85,3872. No furnace insertion into destination barrel 1710,84,3872 exists in the supplied copy. Block placement history independently identifies these positions. The database was not changed or copied into Git.
- Backed up stopped dedicated-server databases, configs and previous jars outside Git; installed alpha.6 on both fixtures.
- Fabric started with the pack's exact Open Parties and Claims 0.32.7 and Forge Config API Port 21.1.6 jars. Exported transformed packet-listener bytecode shows Guardian's read-only use handler before vanilla scheduling/gameplay callbacks. This confirms hook insertion, not a connected-player claims test.
- Both loaders' console lookups verified source/destination route text, oldest/newest filters, pages and compact navigation. Both servers stopped normally. Removed the two temporary Fabric compatibility jars; hopper logging remains disabled on both fixtures.

Connected-player acceptance is still required for claim-denied inspection, permission revocation, both hands, predicted block/item corrections, actual clickable controls, and the pack's client hooks. NeoForge startup does not prove a live packet action. Existing hopper load, sided furnace, chunk-boundary, crafting-correlation and rollback work remains staged after this presentation milestone. No full-pack, load-benchmark or container-rollback completion claim is made.

## Historical alpha.5 validation

# Guardian command and inspector validation

Date: 2026-10-07. Current checkpoint: `0.4.0-alpha.5+1.21.1`.

- All 93 tests passed: 77 common tests and 16 Minecraft tests. Clean build passed, a subsequent normal build reused its configuration cache, and the optional WorldEdit adapter built successfully with Gradle 9.8.0 / Java 21.
- New regression checks cover alias/player/time completion, pagination parsing, SQLite/DuckDB time/radius/selection filters, paged block/item reads, renamed-player UUID queries, registered modded block IDs, readable net container counts, hopper labels and inspected-container scope.
- Both dedicated servers started with the final interaction and exception-safe placement mixins. Console commands verified coordinates, user/time filters, radius, pages, Guardian usage text and readable system history. Both stopped normally.
- Fabric was additionally tested with the exact Lithium 0.15.4 jar from the supplied pack. A temporary chest/hopper/barrel rig transferred three coal, with six unique persisted system transactions and zero capture/write/backpressure failures. Page 2 returned the older matching transfer. Independent database inspection confirmed all six actor/action records.
- Removed fixture contents/blocks and its temporary force-load; removed the temporary Lithium jar and restored the Fabric configuration. Both hopper switches remain false; both servers are stopped with alpha.5 installed. Prior jars/databases/configuration are backed up outside Git.
- Reviewed the supplied client and server logs; the server loaded Guardian alpha.4. Dusty Decorations placement exceptions are documented in MODPACK_COMPATIBILITY.md. No Guardian capture/write errors were found in that log. The exact missing-block case and actual audit database remain unverified.

Connected-player acceptance remains necessary for both inspector buttons, autocomplete in the client, door/switch actions, and claims/protection cancellation. Server startup and console checks do not prove these packet paths. The full 395-entry server pack was not installed into the dedicated fixtures. The small Lithium stream is not a load benchmark or comprehensive chunk-boundary test. Simply opening a menu is not currently recorded as an item transaction; missing past interactions cannot be reconstructed.

## Historical hopper checkpoint

# Guardian Step 4 hopper validation

Date: 2026-10-07. Current checkpoint: `0.4.0-alpha.4+1.21.1`.

## Automated checks

- Clean build passed with Java 21, Gradle 9.8.0, Fabric Loom 1.17.21, and ModDevGradle 2.0.148.
- All 85 tests passed: 71 platform-neutral tests and 14 Minecraft snapshot/codec tests.
- Standard build reused its configuration cache; the separate WorldEdit adapter build passed.
- New tests cover balanced hopper transfers, no-op/failed attempts, reversed or one-sided transfers, changed components, overlapping owners, topology changes, defensive owner copies, and double-chest context.
- SQLite and DuckDB tests preserve system attribution, deduplicate retries, exclude system history from player queries, upgrade schema 4, and reject the new schema through older migrators.
- Runtime/source jar contents, release workflow validation, source ZIP integrity, and SHA-256 were checked.

## Dedicated server transfer acceptance

- Backed up stopped test databases and configuration outside Git before installing the checkpoint.
- Temporarily enabled `logging.automatedContainerTransfers` on the test servers. Configurations use version 6; storage uses schema 5.
- Created a temporary rig in an inspected empty area: double source chest, hopper, double destination chest, plus a separate hopper facing a full incompatible destination.
- Ran actual transfers on Fabric/SQLite, NeoForge/SQLite, and NeoForge/DuckDB. Each run produced 41 unique transactions: 19 successful pulls and 19 pushes through the double chests, plus three successful pulls into the blocked hopper.
- Failed pushes and empty attempts produced no history. Status reported zero audit/write failures and zero backpressure, with all accepted entries persisted.
- Independently decoded persisted payloads after shutdown on each backend. Every transaction conserved one diamond between two physical owners, system attribution was correct, both double-chest halves were indexed, and persistent custom names survived.
- The final guarded Fabric jar also restarted with hopper logging disabled; the temporary rig still transferred items without new audit submissions.
- Removed the temporary blocks and their contents, removed only the force-load added for the fixture, shut servers down normally, and restored NeoForge to SQLite and both automation switches to false.

## Acceptance boundary

This establishes the tested chest/hopper paths, including NeoForge's capability shortcut, and persistence on both backends. The short single-hopper stream is not a large-server pressure benchmark. Furnaces, other sided/modded containers, protection plugins, sealed-loot behavior, and chunk-boundary cases still need dedicated runtime acceptance.

Player clicks, close/drop/swap packets, creative requests, and protection-mod cancellation still need connected-player acceptance on both loaders. Crafting, other automation mechanisms, loose item pickup, entity inventories, unrelated capability storage, disconnect cleanup, and container rollback remain outside this checkpoint. Fluids and entity logging remain outside Step 4. See [the acceptance guide](STEP_4_TESTING.md).

## Historical inventory and creative checkpoint

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
