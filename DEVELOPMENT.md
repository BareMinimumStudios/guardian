# Developing Guardian

Guardian targets server-only Minecraft 1.21.1 on Fabric and NeoForge. Use Java 21, Gradle 9.8.0 and official Mojang mappings. Kotlin is preferred; Java is used for mixins and low-level hooks. Fabric uses Loom 1.17.21; NeoForge uses ModDevGradle 2.0.148.

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

The [workflow](.github/workflows/publish.yml) uses mc-publish for GitHub, Modrinth and CurseForge. Its selected version must match `modVersion` and a dated changelog section. Standard artifact checks enforce loader metadata, SQLite-only packaging and size limits. GitHub uses `GITHUB_TOKEN`; marketplace uploads use `MODRINTH_PROJECT_ID` / `CURSEFORGE_PROJECT_ID` variables and `MODRINTH_TOKEN` / `CURSEFORGE_TOKEN` secrets. Select one destination when retrying a partial upload.

Do not push or publish as part of local cleanup/testing. Connected-client acceptance and the owner's final release decision remain required by [release scope](docs/RELEASE_SCOPE.md).

## Recovery boundary

Item commands are read-only. Journal, saved-image and inventory coordination code supports observation/recovery safeguards and tested internal protocols; its presence does not authorize live item apply. Raw stack/component or world mutation can bypass exclusive ownership. Do not remove persistence protection simply because item apply is disabled, or add generic mutation guards as release requirements. A later item-apply design requires its own explicit scope and enforceable ownership contract.

## Repository hygiene

Keep worlds, logs, databases, credentials, private agent guidance and generated archives outside Git. Preserve licenses, wrapper files, registered mixins and meaningful tests. Historical milestone documents are archived privately; current contracts and machine-readable release evidence remain in this repository.
