# Developing Guardian

Guardian targets server-only Minecraft 1.21.1 on Fabric and NeoForge. Use Java 21, Gradle 9.9.1 and official Mojang mappings. Kotlin is preferred; Java is used for mixins and low-level hooks. Fabric uses Loom 1.17.21; NeoForge uses ModDevGradle 2.0.148.

## Newer Minecraft targets

The owner authorized matching GriefLogger's published Minecraft versions above 1.21.1 on
Fabric and NeoForge. See [version scope and gates](docs/VERSION_SUPPORT.md) and
[the target catalog](gradle/minecraft-targets.json). The first target is 26.2. Existing 1.21.1
builds remain the available baseline. Java 25 is required for 26.x; Java 21 remains the
1.21.x toolchain. Each target requires native capture/save/recovery validation, with explicit
attention to historical item/block payload compatibility across game versions.

## Modules and checks

`common` contains platform-neutral domain/storage/queue/rollback code. `minecraft` contains shared Minecraft adapters, configuration, commands and mixins. The root module supplies Fabric hooks; `neoforge` supplies its loader hooks. Each core jar includes the common output. `worldedit-adapter` is a separate optional GPL Fabric artifact; the BML core must not import WorldEdit.

```bash
./gradlew clean build --no-daemon --warning-mode all
./gradlew build --no-daemon --warning-mode all
./gradlew build --no-daemon --warning-mode all
./gradlew :worldedit-adapter:build --no-daemon --warning-mode all
```

Use `gradlew.bat` on Windows. Clean build and build have different task graphs; repeat plain build to verify configuration-cache reuse. When developing through connected IntelliJ, run these through its Gradle configurations and use MixinMCP for dependency sources and target checks. Refresh the IDE after external edits and validate changed mixins on both loaders.

## Database packaging

Standard jars include SQLite JDBC only and stay below 16 MiB. DuckDB remains a tested optional backend, built explicitly with `-PbundleDuckDb=true`; those artifacts include `with-duckdb` in the name. Install only one variant. Selecting DuckDB in a standard jar does not install a driver, convert data or silently switch backends. A plain JDBC jar in `mods` is not a supported installation method.

Keep the real Maven driver versions. Loom's four-part JDBC semver notices do not justify changing those dependency versions.

## Release workflow

The [release workflow](.github/workflows/publish.yml) uses mc-publish for GitHub and CurseForge and delegates Modrinth to its reusable [upload workflow](.github/workflows/modrinth.yml). The Modrinth workflow covers all selected Minecraft/loader targets; its direct launcher defaults to a dry run. See the [publishing guide](docs/PUBLISHING.md). Its selected version must match `modVersion` and a dated changelog section. Standard artifact checks enforce loader metadata, SQLite-only packaging and size limits. GitHub uses `GITHUB_TOKEN`; marketplace uploads use `MODRINTH_PROJECT_ID` / `CURSEFORGE_PROJECT_ID` variables and `MODRINTH_TOKEN` / `CURSEFORGE_TOKEN` secrets. Select one destination when retrying a partial upload.

Do not push or publish as part of local cleanup/testing. Connected-client acceptance and the owner's final release decision remain required by [release scope](docs/RELEASE_SCOPE.md).

## Recovery boundary

Online item transfer rollback and recorded creative-deletion recovery use separate authority, native inventory coordination and actual saved-state completion. Keep source claims, conflict checks and interruption protection intact. Follow the current item rollback and recovery guides; older read-only restrictions are historical. Full historical inventory backups and item redo are not implemented.

## Repository hygiene

Keep worlds, logs, databases, credentials, private agent guidance and generated archives outside Git. Preserve licenses, wrapper files, registered mixins and meaningful tests. Historical milestone documents are archived privately; current contracts and machine-readable release evidence remain in this repository.
