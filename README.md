<div align="center">

<img src="docs/assets/guardian-banner.png" alt="Guardian shield and wordmark" width="100%">

<br>

<a href="LICENSE"><img alt="BML 1.0" src="https://img.shields.io/badge/LICENSE-BML--1.0-FFFFFF?style=for-the-badge&labelColor=1A1A1A"></a>
<a href="https://github.com/BareMinimumStudios/guardian/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/BareMinimumStudios/guardian/build.yml?style=for-the-badge&logo=githubactions&logoColor=white&label=BUILD&labelColor=1A1A1A"></a>
<a href="https://github.com/BareMinimumStudios/guardian/issues"><img alt="Issues" src="https://img.shields.io/github/issues/BareMinimumStudios/guardian?style=for-the-badge&labelColor=1A1A1A&color=FFFFFF"></a>

Minecraft 1.21.1 · Java 21 · Server-side

</div>

---

## What is Guardian?

Guardian records block changes so server staff can see what happened and roll back unwanted edits. It stores history in SQLite or DuckDB and provides commands for lookup, inspection, and rollback.

Guardian continues the ExProtect prototype. This checkpoint builds for Fabric and NeoForge with shared Mojang-mapped Minecraft code. Item transactions record accepted clicks and close-time cursor returns in supported block-backed menus, plus player inventory-screen moves, standalone drops, offhand swaps, and accepted creative slot changes. Inventory-screen capture requires an empty crafting area. Block-to-block hopper capture is available through an opt-in setting. See [Step 4 testing](docs/STEP_4_TESTING.md) for coverage and remaining acceptance checks.

## At a glance

- Records player block placement and breaking.
- Records correlated item changes from supported block-container clicks, menu closes, player inventory moves, drops, offhand swaps, and creative slot changes.
- Can record hopper transfers between supported block containers, including double chests.
- Keeps audit history across server restarts with SQLite or DuckDB.
- Shows block history through commands or an inspector tool.
- Restores blocks with bounded work per server tick.
- Logs WorldEdit operations through a separate optional adapter.
- Runs on the server; players do not need Guardian installed.

## Installation

For Fabric 1.21.1, use the Guardian jar from `build/libs` with Fabric API, Fabric Language Kotlin, and Fzzy Config. For NeoForge 1.21.1, use the jar from `neoforge/build/libs` with Kotlin for Forge and Fzzy Config. Install the jar for your server loader. The JDBC drivers and shared core are bundled; Fzzy Config stays an external dependency.

For WorldEdit support on Fabric, also install WorldEdit 7.3.8 and the Guardian WorldEdit adapter from `worldedit-adapter/build/libs`. The adapter has its own GPL license; the core uses BML.

LuckPerms and Fabric Permissions API are optional. Guardian falls back to vanilla operator levels when the permissions API is absent. NeoForge uses its built-in permissions API, which also allows a compatible permission handler.

## Commands

| Command | Purpose |
| --- | --- |
| `/guardian lookup` | Search block history with player, time, action, and region filters. |
| `/guardian transactions <x> <y> <z>` | Show recent container transactions at a block position. |
| `/guardian transactions player <name-or-uuid>` | Show recent item transactions for a player. |
| `/guardian inspect` | Toggle the block inspector. |
| `/guardian rollback` | Preview or apply a block rollback. |
| `/guardian status` | Show storage, queue, and capture status. |

`/co` remains an alias. `l`, `i`, and `rb` are the short subcommands. See [the block testing guide](docs/STEP_2C_TESTING.md) for filter examples and rollback checks.

## Configuration and stored data

Fzzy Config manages Guardian's settings under the `guardian` namespace. New installations store databases in the server's `guardian` directory. Permission nodes start with `guardian.`.

Set `logging.automatedContainerTransfers` to true to enable block-to-block hopper history. It defaults to false in this testing checkpoint. The general, logging, and container-transaction master switches also apply. Use block-position lookup for hopper records.

The rename does not automatically move old ExProtect settings or databases. Back up old data before moving it, and update permission grants to the new names. Opening an existing database upgrades it to schema 5. Existing block history, the persisted format marker, and block payload encoding are preserved. Back up the database before upgrading; older builds cannot open schema 5.

## Project structure

| Module | Role |
| --- | --- |
| `common` | Loader-independent snapshots, storage, queues, filters, and rollback decisions. |
| `minecraft` source directory | Shared Mojang-mapped Minecraft adapters, configuration, commands, and mixins. |
| Root Fabric module | Fabric lifecycle, event, and permission hooks. |
| `neoforge` | NeoForge lifecycle, event, and permission hooks. |
| `worldedit-adapter` | Optional Fabric GPL integration with WorldEdit 7.3.8. |

The shared classes are packaged inside each loader runtime jar. You do not need to install a separate `common` jar.

## Building

```bash
./gradlew clean build --no-daemon --warning-mode all
./gradlew build --no-daemon --warning-mode all
./gradlew :worldedit-adapter:build --no-daemon --warning-mode all
```

Use `gradlew.bat` on Windows. The wrapper uses Gradle 9.8.0. Fabric uses Loom 1.17.21; NeoForge uses ModDevGradle. Both compile against official Mojang mappings. See [development notes](DEVELOPMENT.md) for validation and the staged migration.

## Publishing

The [release workflow](.github/workflows/publish.yml) uses [Kira-NT/mc-publish](https://github.com/Kira-NT/mc-publish), following Remnant's publishing approach. Releases use the matching dated section from [CHANGELOG.md](CHANGELOG.md).

GitHub uses `GITHUB_TOKEN`. Marketplace uploads require repository variables `MODRINTH_PROJECT_ID` and `CURSEFORGE_PROJECT_ID`, and secrets `MODRINTH_TOKEN` and `CURSEFORGE_TOKEN`. Choose a single destination when retrying a failed upload.

## License

The core uses the [Bare Minimum License (BML) v1.0](LICENSE). The optional WorldEdit adapter uses [GPL-3.0-or-later](worldedit-adapter/LICENSE). Third-party notices are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
