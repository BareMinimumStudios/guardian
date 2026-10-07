# Guardian baseline validation

Date: 2026-10-07. Current version: `0.3.0-alpha.4+1.21.1`.

## Builds

- Java 21.0.10, Gradle wrapper 9.8.0, Fabric Loom 1.17.21.
- Imported Fix 1 clean build reproduced two DuckDB lookup failures out of 38 tests.
- Repaired clean build passed all 38 tests, including both DuckDB tests.
- Guardian rename clean build passed all 38 tests.
- Shared-core extraction clean build passed all 38 tests across the root and common modules.
- Repeated `build --no-daemon --warning-mode all` reused the configuration cache.
- Separate `:worldedit-adapter:build --no-daemon --warning-mode all` passed.
- Workflow YAML parsed successfully; its embedded release validation ran locally, verified both server-only runtime jars, and extracted the current changelog section.

## Dedicated Fabric server

- Minecraft 1.21.1, Fabric Loader 0.19.5, Java 21.
- Existing dependencies: Fabric API 0.116.17, Fabric Language Kotlin 1.14.1, Fzzy Config 0.7.7, Data Attributes 3.0.3.
- Core startup reached `Done`; SQLite opened at schema 1.
- `/guardian status` reported a running writer with zero failures.
- Shutdown completed normally, and restart reopened SQLite without an unclean-shutdown warning.
- Optional Guardian WorldEdit adapter initialized with the full WorldEdit 7.3.8 production jar; the combined server reached `Done`.
- DuckDB startup also reached `Done`, with schema 1 and a healthy status command.
- The alpha.4 shared-core artifact was then tested with SQLite and the WorldEdit adapter; it reached `Done` and reported a healthy writer without installing a separate common jar.
- Test server was stopped normally. Its original SQLite configuration was restored.

WorldEdit's Maven artifact is a compile dependency, not the complete production jar. The initial attempt with that artifact failed on missing WorldEdit classes. It was replaced with the hash-verified Modrinth production distribution before the successful adapter smoke test.

## Limits

No player was connected during these smoke tests. Live placement, breaking, inspector interaction, rollback application, and WorldEdit edits still need a player-driven acceptance session. The adapter has no automated test source set in this checkpoint. Existing pressure and storage tests cover the core pipeline.

NeoForge support and Step 4 container/item transactions have not been implemented in this baseline. The NeoForge test server was inspected but not modified or launched.

Loom emits notices about OneDrive and the JDBC drivers' four-part Maven versions. These did not cause build, test, or Fabric startup failures. No Kotlin return warnings or deprecated WorldEdit constructor warnings remained after the fixes.

The humanize-text pipeline was reviewed but not run: its LLM and Niutrans credentials are not configured. Documentation was edited directly and checked against the implemented commands and dependencies.
