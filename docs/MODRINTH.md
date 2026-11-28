<p align="center" style="text-align: center;">
<img src="https://raw.githubusercontent.com/BareMinimumStudios/guardian/main/docs/assets/guardian-banner.png" alt="Guardian shield and wordmark" width="1080">
</p>

<p align="center" style="text-align: center;">
<a href="https://modrinth.com/mod/guardians"><img src="https://img.shields.io/badge/Modrinth-242424?logo=modrinth&amp;logoColor=F5F5F5&amp;style=for-the-badge" alt="Modrinth" height="28"></a>
<a href="https://www.curseforge.com/minecraft/mc-mods/guardianex"><img src="https://img.shields.io/badge/CurseForge-323232?logo=curseforge&amp;logoColor=F5F5F5&amp;style=for-the-badge" alt="CurseForge" height="28"></a>
<a href="https://github.com/BareMinimumStudios/guardian"><img src="https://img.shields.io/badge/Source-1A1A1A?logo=github&amp;logoColor=F5F5F5&amp;style=for-the-badge" alt="GitHub source" height="28"></a>
<a href="https://github.com/BareMinimumStudios/guardian/issues"><img src="https://img.shields.io/github/issues/BareMinimumStudios/guardian?style=for-the-badge&amp;labelColor=1A1A1A&amp;color=404040" alt="Report an issue" height="28"></a>
<a href="https://github.com/BareMinimumStudios/guardian/forks"><img src="https://img.shields.io/github/forks/BareMinimumStudios/guardian?style=for-the-badge&amp;labelColor=1A1A1A&amp;color=404040" alt="Forks" height="28"></a>
<a href="https://github.com/BareMinimumStudios/guardian/stargazers"><img src="https://img.shields.io/github/stars/BareMinimumStudios/guardian?style=for-the-badge&amp;labelColor=1A1A1A&amp;color=404040" alt="Stars" height="28"></a>
</p>

<p align="center" style="text-align: center;"><strong>Fabric + NeoForge · Server-side</strong></p>

Guardian gives server staff a searchable history of what happened in their world. Find out who broke a block, took items from a chest, or edited a sign. It runs on Fabric and NeoForge servers, and players can join without installing it.

## Find out what happened

- **Inspect in-world.** Run `/guardian inspect`. Left-click for block history or right-click a container for item history. Use `/guardian inspect ui` for a read-only item viewer.
- **Search an area.** `/guardian lookup r:10` shows block history within 10 blocks. `/guardian transactions r:10` searches item activity in the same area. Add player or time filters when you need them.
- **Review staff logs.** Configure logging for chat, sessions, commands, and vanilla whispers. Guardian also tracks supported sign edits, entity activity, and world changes.

## Undo changes and recover items

- **Preview a rollback** with `/guardian rollback preview` for blocks or `/guardian rollback-items preview` for item transfers. Use `/guardian restore` or `/guardian restore-items` to redo supported changes.
- **Recover recorded creative deletions** with `/guardian recover-items`.
- **Restore a saved inventory** by finding a checkpoint with `/guardian inventory list` and checking it with `/guardian inventory restore preview`. A completed restore saves an undo checkpoint.

Manual and optional automatic inventory checkpoints cover inventory, armor, offhand, ender chest, and supported equipment slots from Trinkets, Curios, Accessories, Cosmetic Armor Reworked, Tool Belt, Backpacked, and Traveler’s Backpack. Equipment support depends on the loader and mod version. See the [inventory guide](https://github.com/BareMinimumStudios/guardian/blob/main/docs/INVENTORY_BACKUPS.md) for setup, undo, and recovery.

## Install on your server

1. Choose the download for your Minecraft version and loader. Guardian targets **1.21.1, 1.21.4–1.21.11, 26.1.2, and 26.2**. Use Java 21 for 1.21.x and Java 25 for 26.x.
2. Install the dependencies listed for your download. Fabric uses Fabric API, Fabric Language Kotlin, and Fzzy Config. NeoForge uses Kotlin for Forge and Fzzy Config.
3. Start the server and run `/guardian status` as an operator.

SQLite is included. Stop the server before copying `guardian/guardian.sqlite` for a backup or upgrade. See the [version support matrix](https://github.com/BareMinimumStudios/guardian/blob/main/docs/VERSION_SUPPORT.md) for loader requirements.

## Optional integrations

- **Open Parties and Claims 0.33.0** can protect configured structures from player claims, with a configurable 50-block buffer by default. [Setup guide](https://github.com/BareMinimumStudios/guardian/blob/main/docs/STRUCTURE_CLAIMS.md)
- **WorldEdit 7.3.8** is supported through a separate GPL adapter for Fabric 1.21.1. [Adapter guide](https://github.com/BareMinimumStudios/guardian/blob/main/worldedit-adapter/README.md)

## Help and project links

[Commands and permissions](https://github.com/BareMinimumStudios/guardian/blob/main/docs/COMMANDS.md) · [Changelog](https://github.com/BareMinimumStudios/guardian/blob/main/CHANGELOG.md) · [Discord](https://discord.gg/pcRw79hwey) · [License](https://github.com/BareMinimumStudios/guardian/blob/main/LICENSE)

## Sponsor

<p align="center" style="text-align: center;">
<a href="https://bisecthosting.com/bareminimum"><img src="https://www.bisecthosting.com/partners/custom-banners/db76a74a-a111-4660-98b7-5a75c15a5951.png" alt="BisectHosting sponsor banner" width="1080"></a>
</p>

Use code **`bareminimum`** to get **25% off your first month**!
