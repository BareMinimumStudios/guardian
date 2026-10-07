# Developing Guardian

Guardian is built with Kotlin, Java 21, and Gradle 9.8.0. Java is reserved for mixins and low-level hooks. The Fabric build uses Loom 1.17.21.

## Current milestone

The imported Step 3 Fix 1 source is the baseline. Its two DuckDB lookup failures were reproduced and repaired before the rename. All 38 tests then passed, a repeated `build` reused the configuration cache, and the separate WorldEdit adapter build passed.

The core and optional WorldEdit adapter remain separate artifacts with separate licenses. Do not introduce WorldEdit imports into the core.

## Next milestones

1. Completed: the platform-neutral domain, storage, queues, filters, and rollback decisions now live in `common`. Minecraft lifecycle, events, permissions, and configuration remain in the Fabric platform module.
2. Add a NeoForge 1.21.1 implementation and verify block capture, queries, rollback, shutdown, and restart on both dedicated servers.
3. Add immutable, registry-aware item snapshots using Minecraft's ItemStack codec and Data Components. Correlate all slot changes from one accepted container action under one transaction ID.
4. Persist and query container transactions, then exercise ordinary clicks, shift clicks, swaps, splits, drag actions, cancellation, disconnects, and restart recovery.

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

The shared-core extraction is version `0.3.0-alpha.4+1.21.1`. It keeps the existing database schema and persisted format unchanged.
