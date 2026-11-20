<div align="center">

<img src="docs/assets/guardian-banner.png" alt="Guardian shield and wordmark" width="100%">

<br>

<a href="LICENSE"><img alt="BML 1.0" src="https://img.shields.io/badge/LICENSE-BML--1.0-FFFFFF?style=for-the-badge&labelColor=1A1A1A"></a>
<a href="https://github.com/BareMinimumStudios/guardian/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/BareMinimumStudios/guardian/build.yml?style=for-the-badge&logo=githubactions&logoColor=white&label=BUILD&labelColor=1A1A1A"></a>
<a href="https://github.com/BareMinimumStudios/guardian/issues"><img alt="Issues" src="https://img.shields.io/github/issues/BareMinimumStudios/guardian?style=for-the-badge&labelColor=1A1A1A&color=FFFFFF"></a>

Minecraft 1.21.1 · Java 21 · Server-side

</div>

---

## Guardian

See who changed a block, moved items or edited a sign. Inspect the history in-game, search by
player and time, and undo supported changes. Search broadly or add optional filters to narrow the results.

**Minecraft 1.21.1 · Fabric and NeoForge · Java 21 · Server only**

Players do not need to install anything. Current build: **alpha.101** (testing release).

Alpha.100 testing builds target **1.21.1, 1.21.4 to 1.21.11, 26.1.2 and 26.2**.
Choose the file for your exact Minecraft version and loader. Newer ports remain experimental;
see the [version support matrix](docs/VERSION_SUPPORT.md) for validation limits.

## Install

1. Stop your server and back up the world.
2. Put the matching Guardian jar and the dependencies below in the server's `mods` folder.
   When updating, remove the old Guardian jar.
3. Start the server and run `/guardian status` as an operator.

The dependency versions below are for **1.21.1**. For newer targets, use the dependencies
listed with that exact download.

| Server | Install alongside Guardian |
| --- | --- |
| Fabric | Fabric Loader 0.19.5+, Fabric API 0.116.17+1.21.1, Fabric Language Kotlin 1.14.1+kotlin.2.4.20, Fzzy Config 0.7.6+1.21 |
| NeoForge | NeoForge 21.1.256+, Kotlin for Forge 5.12+, Fzzy Config 0.7.6 for NeoForge 1.21.1 |

SQLite is included. Your history is saved in `guardian/guardian.sqlite` inside the server folder.
Keep this database with your world backups; stop the server before copying it.

Using WorldEdit on Fabric? Install the separate [WorldEdit adapter](worldedit-adapter/README.md)
with WorldEdit 7.3.8. **The adapter is Fabric only; do not install it on NeoForge.**

## Inspect a block or container

Run:

```text
/guardian inspect
```

- **Left-click a block** to see its block history.
- **Right-click a container** to see items added and removed.
- **Click a chest or hopper minecart** to see that cart's item history, even after it moves.
- Run `/guardian inspect ui` to view the selected container's item changes in a menu.
- Run `/guardian inspect` again to return to normal interaction.

The item viewer is read-only. Use **Previous/Next transaction** to move through results and
**Previous/Next changed slots** when one transaction has more slots to show.

## Find out what happened

**Search filters are optional.** You do not need a player name, time window or item ID.
Start with a broad search:

```text
/guardian lookup r:10
/guardian transactions r:10
/guardian whispers
```

These show block changes within ten blocks, item movements within ten blocks, and
recorded whispers from everyone, respectively. Radius searches include all players and
all retained ages unless you add more filters. Each command searches its own history
category; a block lookup does not combine blocks, items and whispers into one result list.

You can also run `/guardian lookup`, `/guardian transactions`, `/guardian chat`,
`/guardian sessions`, `/guardian commands` or `/guardian whispers` with no filters.
Without a radius, block lookup covers your current dimension; item history can cover all
dimensions. Chat, sessions, commands and whispers are server-wide.

Results are paged, newest first by default—not all printed at once. Use **Next/Previous**
or add `p:2`. Logging must have been enabled when the event happened, and staff permissions
still apply.

To narrow a search, add only the filters you need. Replace `Alex` with the player's name:

```text
/guardian lookup u:Alex t:1h r:10
/guardian transactions u:Alex t:1h r:10
```

The first searches block history; the second searches item movements.
Both look back one hour within ten blocks of where you stand.

| Filter | What it means | Example |
| --- | --- | --- |
| `u:` | Player | `u:Alex` |
| `t:` | Time to look back | `t:30m`, `t:2h`, `t:7d` |
| `r:` | Distance from you, measured in each direction | `r:10` |
| `i:` | Block or item ID | `i:minecraft:coal` |
| `p:` | Result page | `p:2` |

Use the chat's **Next/Previous** links for more results. Tab completion helps with names
and filters. See the [command guide](docs/COMMANDS.md) for other searches and filter combinations.

Guardian only knows about events recorded while the relevant logging was enabled.
It cannot recover history from before installation.

## Recover a complete inventory

Choose a checkpoint saved **before the loss**. Start by listing existing backups:

```text
/guardian inventory list u:Alex
/guardian inventory restore preview <checkpoint UUID>
/guardian inventory restore apply <checkpoint UUID>
/guardian inventory restore confirm <token>
```

For a new backup, use `/guardian inventory snapshot u:Alex` while Alex is online with
menus closed. Taking one after a clear only saves that empty state. Automatic
checkpoints are off by default. Full inventory restoration requires operator
level 4 or `guardian.inventory.restore` plus `guardian.inventory`. It replaces the
player's inventory and saves a before-state for undo; it does not reverse items moved
elsewhere. See [inventory backups](docs/INVENTORY_BACKUPS.md) for setup and recovery.

## Undo changes

Back up first and preview a small area.

**Blocks:**

```text
/guardian rollback preview u:Alex t:10m r:5
/guardian rollback u:Alex t:10m r:5
```

To put rolled-back blocks back, use `/guardian restore preview` and `/guardian restore`
with the same filters.

**Item transfers:**

```text
/guardian rollback-items preview u:Alex t:10m r:5
/guardian rollback-items apply u:Alex t:10m r:5
/guardian rollback-items confirm <token shown by apply>
```

Item rollback reverses a recorded transfer; it does not restore a whole past inventory.
Affected players must be online, with menus closed and nothing on their cursor or crafting grid.
Keep the containers loaded and inside the chosen area. Start with a recent shift-click
transfer between a player and a vanilla chest or barrel.

Read the [item rollback guide](docs/ONLINE_ITEM_ROLLBACK.md) before using it on live history.
Minecarts, direct item displays and lectern book actions are not supported rollback targets.
To undo a completed item rollback, use `/guardian restore-items` to find its operation UUID,
then preview, apply and confirm the redo. All involved inventories must still match the
saved rollback result. See the [item redo guide](docs/ITEM_REDO.md).

**Deleted items in creative:**

Use the separate recovery command for recorded creative item deletions:

```text
/guardian recover-items preview u:Alex t:10m
/guardian recover-items apply u:Alex t:10m
/guardian recover-items confirm <token shown by apply>
```

Alex must stay online with menus closed and the deleted slots empty. Choose a short
window containing the deletion. Recovery restores the recorded item data, including
names and damage. It refuses conflicting history, occupied slots and repeat recovery.
This requires `guardian.recover.items` and `guardian.rollback`, or operator level 3.

This recovers supported creative deletions recorded by Guardian, not a whole past
inventory. It cannot recover unlogged items, consumed items or history from before
installation. Only player and time filters apply; there is no radius filter because
inventory deletion has no container location. [Recovery details](docs/CREATIVE_ITEM_RECOVERY.md).

## Choose what to log

Player block changes, supported item transactions and login/logout events are enabled by default.
Other categories are optional. Edit Guardian's generated server configuration under `config/guardian`
and restart the server.

| To record… | Enable… |
| --- | --- |
| Hopper transfers, including minecarts | `logging.automatedContainerTransfers` |
| Sign text, dye, glow and wax; supported plaques | `logging.signTextChanges` |
| Lectern books, opening and page turns | `logging.lecternInteractions` |
| Public chat | `activity.chat` |
| Player commands | `activity.commands` |
| Vanilla `/msg`, `/tell` and `/w` | `activity.whispers` |
| Staff live spy (also needs the matching logging switch) | `activity.liveSpy` |
| Entity deaths, names, taming, breeding or item frames | The matching switch under `entities` |
| Bucket changes or natural fluid flow | The matching switch under `fluids` |
| Fire, explosions, growth, decay, pistons or falling blocks | The matching switch under `environment` |

Copper waxing and axe scraping use the default player block logging.
Lectern book insertion/removal also appears in item transactions.
Optional environmental, entity, sign and lectern history is for investigation; its presence
does not mean that category supports rollback.

Modded blocks and items are recorded when they use supported interaction and inventory paths.
Custom storage and menus may need compatibility work.

## Whisper history and live spy

These are **off by default**. Enable `activity.whispers` in the server config and restart:

```text
/guardian whispers u:Alex t:1h
```

Run `/guardian whispers` alone for recorded whispers from everyone, or add `u:Alex` to find
whispers sent **or received** by Alex through vanilla `/msg`, `/tell` and `/w`.
Public chat history stays separate. To watch new messages, also enable `activity.liveSpy`:

```text
/guardian spy whispers on
/guardian spy whispers off
```

Filters are optional: `/guardian spy whispers on` watches everyone. To focus your own feed:

```text
/guardian spy whispers on u:Alex,!Bob
/guardian spy commands on c:!help,!list
/guardian spy status
/guardian spy off
```

Personal filters hide live updates only; they do not change saved history. `spy off` stops
all your channels. History and spy entries use short, labelled previews; hover for the full
text, exact timestamp and record IDs.

To find literal text in public chat, whispers or command history:

```text
/guardian whispers t:1h q:"two words"
/guardian commands t:1h q:teleport
```

Matches are highlighted. Text search needs a time window of at most seven days and supports
the first 100 pages. Other searches still work without filters.

For ordinary commands, enable `activity.commands` and use `/guardian spy commands on`.
Arguments stay hidden unless `activity.commandArguments` is enabled. Authentication commands
are excluded. Spy subscriptions end on logout/restart and require the matching history permission
plus `guardian.spy` (operator level 3 by default).

Tell players in your server rules what is recorded. Restrict staff access and protect database
backups and exports. See the [command guide](docs/COMMANDS.md#whispers-and-live-spy) for limits.
Back up before updating: alpha.101 upgrades the database to schema 30; older jars cannot open it.

## Slow searches or item rollback?

Guardian runs database searches in the background. Alpha.96 improves radius searches and
loads transaction locations together to reduce repeated reads. It keeps every rollback safety
check and waits for actual inventory saves.

After a slow request, run `/guardian status`. Its `Guardian timing` lines separate queueing,
database work, safety checks and saving. Include those lines, the exact command and the stage
that felt slow when reporting a problem. See the [timing guide](docs/COMMANDS.md#transaction-and-item-rollback-timings).
Timings contain no player names, messages or item contents.

## More help

- [Command guide](docs/COMMANDS.md): chat, entities, fluids, filters, permissions, exports and deleting old history.
- [Publishing builds](docs/PUBLISHING.md) for maintainers.
- [Item rollback](docs/ONLINE_ITEM_ROLLBACK.md) and [interrupted-operation recovery](docs/ITEM_RECOVERY.md).
- [WorldEdit setup](worldedit-adapter/README.md).
- [Changelog](CHANGELOG.md) and [test/release status](docs/VALIDATION.md).
- [Report a problem](https://github.com/BareMinimumStudios/guardian/issues).

No results? Check `/guardian status`, the logging switch, player name, time window and dimension.
For item history, actually move an item; opening a chest alone adds no transaction.
Commands missing? Check your operator level or [permissions](docs/COMMANDS.md#permissions).

This is an alpha release. Real-client and modpack testing is still in progress.
When reporting a problem, include the exact command, Guardian version and relevant log.
For item rollback, include the specific **Player…** or **Container…** diagnostic beneath the summary.

The core uses the [Bare Minimum License](LICENSE). The optional WorldEdit adapter uses
[GPL-3.0-or-later](worldedit-adapter/LICENSE).
[Development documentation](DEVELOPMENT.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)
