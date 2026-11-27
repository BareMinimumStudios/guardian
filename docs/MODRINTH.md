![Guardian shield and wordmark](https://raw.githubusercontent.com/BareMinimumStudios/guardian/main/docs/assets/guardian-banner.png)

## Guardian 0.4.0

See who changed a block, moved an item, or edited a sign. Guardian runs on Fabric and NeoForge servers. Players can join without installing it.

### Install

Choose the file for your exact loader and Minecraft version. Builds target 1.21.1, 1.21.4–1.21.11, 26.1.2, and 26.2. Use Java 21 for 1.21.x and Java 25 for 26.x. Fabric requires Fabric API, Fabric Language Kotlin, and Fzzy Config. NeoForge requires Kotlin for Forge and Fzzy Config. Run /guardian status as an operator after startup. SQLite is included. Stop the server before copying guardian/guardian.sqlite or upgrading.

### Inspect and recover

- Run /guardian inspect. Left-click a block for block history, right-click a container for item history, or open the read-only viewer with /guardian inspect ui.
- Search every player in an area with /guardian lookup r:10 or /guardian transactions r:10. Player and time filters are optional.
- Preview supported block and item rollbacks with /guardian rollback preview or /guardian rollback-items preview. Block redo uses /guardian restore. Item redo uses /guardian restore-items.
- Recover recorded creative deletions with /guardian recover-items. Use /guardian inventory list to find a saved checkpoint, then /guardian inventory restore preview to check it before applying. A completed restore saves an undo checkpoint.

### Backups and integrations

Manual and optional automatic checkpoints cover inventory, armor, offhand, ender chest, and supported equipment from Trinkets, Curios, Accessories, Cosmetic Armor Reworked, Tool Belt, Backpacked, and Traveler’s Backpack. Support depends on loader and mod version. Guardian preserves supported item components and can repair interrupted restores through targeted recovery.

Staff can configure logging for chat, sessions, commands, and vanilla whispers. Guardian also records supported signs, entities, fluids, and other world changes. Optional Open Parties and Claims 0.33.0 integration protects configured structures with a 50-block buffer by default. The separate GPL WorldEdit adapter supports Fabric 1.21.1 with WorldEdit 7.3.8.

### Downloads and support

- [Modrinth](https://modrinth.com/mod/guardians) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/guardianex)
- [GitHub source and README](https://github.com/BareMinimumStudios/guardian) · [Changelog](https://github.com/BareMinimumStudios/guardian/blob/main/CHANGELOG.md) · [Report an issue](https://github.com/BareMinimumStudios/guardian/issues)
- [Commands and permissions](https://github.com/BareMinimumStudios/guardian/blob/main/docs/COMMANDS.md) · [Version support](https://github.com/BareMinimumStudios/guardian/blob/main/docs/VERSION_SUPPORT.md)
- [Inventory backups and undo](https://github.com/BareMinimumStudios/guardian/blob/main/docs/INVENTORY_BACKUPS.md) · [Item rollback](https://github.com/BareMinimumStudios/guardian/blob/main/docs/ONLINE_ITEM_ROLLBACK.md) · [Interruption recovery](https://github.com/BareMinimumStudios/guardian/blob/main/docs/ITEM_RECOVERY.md)
- [Structure claim protection](https://github.com/BareMinimumStudios/guardian/blob/main/docs/STRUCTURE_CLAIMS.md) · [WorldEdit adapter](https://github.com/BareMinimumStudios/guardian/blob/main/worldedit-adapter/README.md) · [Bare Minimum License](https://github.com/BareMinimumStudios/guardian/blob/main/LICENSE)
