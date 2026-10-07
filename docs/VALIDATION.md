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
