![Guardian](https://raw.githubusercontent.com/BareMinimumStudios/guardian/main/docs/assets/guardian-banner.png)

# Guardian

Find out who changed blocks, moved items or edited signs. Inspect history in-game, search an area and undo supported changes.

**Server-side for Fabric and NeoForge. Players do not need to install Guardian.**

## What can it do?

- Inspect block history and item movements, including a read-only transaction viewer.
- Search by area, player, time or block/item ID.
- Roll back supported block edits and item transfers; redo rolled-back blocks.
- Recover recorded creative item deletions with names and other item data intact.
- Optionally record chat, commands, whispers, signs, entity events and environmental changes.

Modded storage and interactions are supported when they use compatible paths. Custom menus may need additional compatibility work.

## Install

1. Stop the server and back up the world and Guardian database.
2. Add the Guardian jar for your **exact Minecraft version and loader** to `mods`. Remove the old Guardian jar when updating.
3. Install the matching dependencies below, start the server and run `/guardian status` as an operator.

| Loader | Required dependencies |
| --- | --- |
| Fabric | Fabric API, Fabric Language Kotlin, Fzzy Config |
| NeoForge | Kotlin for Forge, Fzzy Config |

SQLite is included. History is saved in `guardian/guardian.sqlite`; stop the server before copying it for a backup.

**Alpha.99 is a testing release.** Targets: 1.21.1, 1.21.4–1.21.11, 26.1.2 and 26.2 on both loaders. Check the Versions tab for uploaded files. Use Java 21 for 1.21.x and Java 25 for 26.x. Back up before updating: schema 27 cannot be opened by older Guardian builds.

## Inspect something

Run `/guardian inspect`, then:

- **Left-click a block** for block history.
- **Right-click a container** for item history.
- Use `/guardian inspect ui` for the read-only item viewer.

Run `/guardian inspect` again to return to normal interaction.

## Search an area or player

**History filters are optional.** You can search everyone in an area without supplying a player or time:

```text
/guardian lookup r:10
/guardian transactions r:10
```

These search block and item history within ten blocks, across all players and retained ages. Each command searches its own category.

Narrow the results when needed:

```text
/guardian lookup u:Alex t:1h r:10
/guardian transactions i:minecraft:coal r:5
```

`u:` = player, `t:` = time, `r:` = radius, `i:` = block/item ID. Use **Next/Previous** or `p:2` for more results. Tab completion helps with names and filters.

## Undo recorded changes

Preview a small area first:

```text
/guardian rollback preview u:Alex t:10m r:5
/guardian rollback u:Alex t:10m r:5
```

Use `/guardian restore preview` and `/guardian restore` with the same filters to redo rolled-back blocks.

For supported item transfers:

```text
/guardian rollback-items preview u:Alex t:10m r:5
/guardian rollback-items apply u:Alex t:10m r:5
/guardian rollback-items confirm <token>
```

Keep affected players online, menus closed and cursors/crafting grids empty. Containers must stay loaded and inside the area. Use the token returned by `apply`.

### Deleted items in creative

For recorded creative item deletions, use the separate recovery command:

```text
/guardian recover-items preview u:Alex t:10m
/guardian recover-items apply u:Alex t:10m
/guardian recover-items confirm <token>
```

Keep Alex online, menus closed and the deleted slots empty. Recovery refuses occupied slots, conflicting history and repeat recovery. It accepts a player and time window, without a radius.

**This is not a full inventory backup.** Guardian cannot recover unlogged items or earlier events. Item redo and rollback of minecarts, direct displays and lectern books are unsupported. Read the [item rollback](https://github.com/BareMinimumStudios/guardian/blob/main/docs/ONLINE_ITEM_ROLLBACK.md) and [creative recovery](https://github.com/BareMinimumStudios/guardian/blob/main/docs/CREATIVE_ITEM_RECOVERY.md) guides before using them on live history.

## Optional logging

Edit `config/guardian` and restart the server. Logging must be enabled before an event happens.

For vanilla `/msg`, `/tell` and `/w`, enable `activity.whispers` and run `/guardian whispers`. No filters are required. For live monitoring, also enable `activity.liveSpy`, then use `/guardian spy whispers on` or `/guardian spy commands on`. Stop with `/guardian spy off`.

Commands require staff permissions. Restrict private-history access and tell players what you record. Logging an event does not automatically make it recoverable.

## WorldEdit and help

WorldEdit integration requires the **separate optional GPL adapter**, currently for **Fabric 1.21.1 with WorldEdit 7.3.8 only**. Do not install that adapter on NeoForge or newer Minecraft versions.

- [Full command guide](https://github.com/BareMinimumStudios/guardian/blob/main/docs/COMMANDS.md)
- [WorldEdit setup](https://github.com/BareMinimumStudios/guardian/blob/main/worldedit-adapter/README.md)
- [Changelog](https://github.com/BareMinimumStudios/guardian/blob/main/CHANGELOG.md)
- [Report an issue](https://github.com/BareMinimumStudios/guardian/issues)

Report problems with your Guardian version, exact command and diagnostic message. Real-client and modpack testing is still in progress.

Guardian's core uses the Bare Minimum License. The optional WorldEdit adapter is licensed separately under GPL-3.0-or-later.
