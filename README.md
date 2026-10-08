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

Guardian records block changes so server staff can see what happened and roll back unwanted edits. It stores history in SQLite and provides commands for lookup, inspection, and rollback.

Guardian continues the ExProtect prototype. This checkpoint builds for Fabric and NeoForge with shared Mojang-mapped Minecraft code. Item transactions record accepted clicks and close-time cursor returns in supported block-backed menus, plus player inventory-screen moves, standalone drops, offhand swaps, and accepted creative slot changes. Vanilla 2×2 and 3×3 crafting captures ingredient grids, recipe-book placement, result takes and close-time returns. Recipe previews are excluded from stored item counts; unknown or extended crafting menus are skipped. Block-to-block hopper capture is available through an opt-in setting. See [Step 4 testing](docs/STEP_4_TESTING.md) for coverage and remaining acceptance checks.

## At a glance

- Records player block placement and breaking.
- Records correlated item changes from supported block-container clicks, menu closes, player inventory moves, drops, offhand swaps, creative slot changes, and vanilla crafting.
- Can record hopper transfers between supported block containers, including double chests.
- Keeps audit history across server restarts with bundled SQLite.
- Shows block history through commands or an inspector tool.
- Restores blocks with bounded work per server tick.
- Previews eligible container transfers without changing items; item rollback apply is still in development.
- Logs WorldEdit operations through a separate optional adapter.
- Runs on the server; players do not need Guardian installed.

## Installation

For Fabric 1.21.1, use the Guardian jar from `build/libs` with Fabric API, Fabric Language Kotlin, and Fzzy Config. For NeoForge 1.21.1, use the jar from `neoforge/build/libs` with Kotlin for Forge and Fzzy Config. Install the jar for your server loader. SQLite and the shared core are bundled; Fzzy Config stays an external dependency. Standard core jars are about 12 MiB. DuckDB is an optional build-time integration; see [database packaging](docs/DISTRIBUTION_SIZE.md).

For WorldEdit support on Fabric, also install WorldEdit 7.3.8 and the Guardian WorldEdit adapter from `worldedit-adapter/build/libs`. The adapter has its own GPL license; the core uses BML.

LuckPerms and Fabric Permissions API are optional. Guardian falls back to vanilla operator levels when the permissions API is absent. NeoForge uses its built-in permissions API, which also allows a compatible permission handler.

## Commands

| Command | Purpose |
| --- | --- |
| `/guardian lookup` | Search block history with player, time, action, and region filters. |
| `/guardian transactions <x> <y> <z>` | Show recent container transactions at a block position. |
| `/guardian transactions u:<player> t:1h l:20 p:1` | Filter and page through item history. |
| `/guardian transactions player <name-or-uuid>` | Compatibility form for recent player item history. |
| `/guardian inspect` | Toggle inspection: left-click for block history, right-click a container for item history. |
| `/guardian rollback` | Preview or apply a block rollback. |
| `/guardian rollback-items preview t:1h r:5` | Check item rollback candidates without changing items. |
| `/guardian status` | Show storage, queue, and capture status. |

`/co` remains an alias. `l`, `i`, and `rb` are the short subcommands. See [the block testing guide](docs/STEP_2C_TESTING.md) for filter examples and rollback checks.

## Configuration and stored data

Fzzy Config manages Guardian's settings under the `guardian` namespace. New installations store databases in the server's `guardian` directory. Permission nodes start with `guardian.`.

Set `logging.automatedContainerTransfers` to true to enable block-to-block hopper history. It defaults to false in this testing checkpoint. The general, logging, and container-transaction master switches also apply. Use block-position lookup for hopper records.

The rename does not automatically move old ExProtect settings or databases. Back up old data before moving it, and update permission grants to the new names. Opening an existing database upgrades it to schema 6. Crafting transactions use a new versioned slot encoding; existing item and block history remains readable. Existing block history, the persisted format marker, and block payload encoding are preserved. Back up the database before upgrading; older builds cannot open schema 6.

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

Filter arguments offer tab completion for online players, time units and common values. Both `u:`/`user:` and `t:`/`time:` work. Block lookup searches the current dimension; item lookup by user spans recorded dimensions unless a position/radius is supplied. Results are paged: keep the same filters and add `p:2`, `p:3`, etc. Items added to a container appear in green, removals in red; automated transfers name the hopper. Opening a container without moving items does not create an item transaction. Accepted door, gate, trapdoor, lever and button state changes appear in block history.

History results include clickable **Previous**, **Next**, and **Oldest/Newest first** controls. You can also type `o:oldest` or `order:newest`. Inspector pages contain at most five transactions; `/guardian inspect page 2` and `/guardian inspect order oldest` provide keyboard alternatives. Hopper records display the observed source and destination coordinates together. A pull into a hopper does not establish delivery to the inventory below it.

Inspection intercepts scheduled server packets before normal block interaction callbacks and requires `guardian.inspect` on every click/page. It acknowledges canceled actions, restores predicted block/item state, stays within block reach, and avoids loading chunks. Ordinary interactions with inspection off continue through claim protection. Retest protected-container inspection with your actual client/modpack after installing this checkpoint.
