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

Guardian is a server-side audit mod for Minecraft 1.21.1 on Fabric and NeoForge. It records supported block changes and item movements so staff can investigate incidents, inspect containers and roll back recorded block edits. History is stored locally in SQLite.

**Current version: 0.4.0-alpha.62+1.21.1.** This is an alpha for testing in a backed-up world. Automated and server-side permission checks have passed; connected-client acceptance and final release sign-off remain pending. Players do not need Guardian or Fzzy Config on their clients.

## Install or update

Use Java 21 and install the jar matching your **server** loader. Each core jar is about 12 MiB and includes SQLite; no separate database mod or common-module jar is needed.

| Server | Guardian jar | Required server dependencies |
| --- | --- | --- |
| Fabric 1.21.1 | `guardian-<version>.jar` | Fabric Loader 0.19.5 or newer, Fabric API 0.116.17+1.21.1, Fabric Language Kotlin 1.14.1+kotlin.2.4.20, Fzzy Config 0.7.6+1.21 |
| NeoForge 1.21.1 | `guardian-neoforge-<version>.jar` | NeoForge 21.1.256 or newer, Kotlin for Forge 5.12 or newer, Fzzy Config 0.7.6 for NeoForge 1.21.1 |

1. Stop the server. Back up its world, configuration and Guardian database.
2. Put the matching Guardian jar and dependencies in the server's `mods` folder. Remove the old Guardian jar when updating; leave only one Guardian core installed.
3. Start the server and check its log for dependency or storage errors.
4. As an authorized staff member, run `/guardian status`. Check that storage is available before relying on new history.

For optional **Fabric WorldEdit** capture, also install WorldEdit 7.3.8 and the separate `guardian-worldedit-<version>.jar`. Do not install the Fabric adapter on NeoForge. See the [adapter guide](worldedit-adapter/README.md).

### Data and backups

The default database is `<server directory>/guardian/guardian.sqlite`. Fzzy Config generates Guardian's settings under the `guardian` namespace in the server's configuration directory. Back up both along with your world.

Stop the server before copying its database for a simple consistent backup. Startup upgrades existing supported databases to **schema 10**. Keep the pre-upgrade backup: an older Guardian build cannot open a schema newer than it supports. Changing database backends does not convert or move history. Old ExProtect files are not moved automatically.

## First investigation

After installing Guardian, place and break a block in a small test area, then run:

```text
/guardian lookup u:YourName t:10m r:5
```

This searches recorded block history in a cuboid extending five blocks from your current position. Guardian can only show events captured while logging was enabled; installing it does not reconstruct older changes.

To inspect a particular block or container:

```text
/guardian inspect
```

- **Left-click** a block for its block history.
- **Right-click** a supported container for its item history.
- Run `/guardian inspect` again to turn inspection off before normal building or container use.

Inspection requires permission on every click and page. Protected-claim inspection and client prediction still need acceptance with your actual pack. With inspection off, ordinary interactions remain subject to claim protection.

## Search block history

```text
/guardian lookup u:YourName t:1h r:10
/guardian lookup t:30m a:break r:5
/guardian lookup t:1d x:100 y:64 z:-20 r:3 l:20 p:1
```

Block lookup searches the dimension in which the command runs. Without a radius or position it can search the whole current dimension. Supply all three `x:`, `y:` and `z:` coordinates together. Tab completion offers online player names and common filter values; recorded offline names can be typed manually.

| Filter | Meaning | Example |
| --- | --- | --- |
| `u:` or `user:` | Recorded player name | `u:YourName` |
| `t:` or `time:` | How far back to search | `t:30m`, `t:2h`, `t:1d12h` |
| `r:` or `radius:` | Cuboid radius around you or explicit coordinates | `r:10` |
| `x: y: z:` | Explicit block coordinates | `x:100 y:64 z:-20` |
| `a:` or `action:` | Block action; lookup and block rollback only | `a:place`, `a:break`, `a:change`, `a:place,break` |
| `l:` or `limit:` | Results per history page | `l:20` |
| `p:` or `page:` | History page, starting at 1 | `p:2` |
| `o:` or `order:` | History sort order | `o:oldest`, `o:newest` |

Time units are `s` seconds, `m` minutes, `h` hours, `d` days and `w` weeks. Values must be positive. Configured result/radius limits still apply. With the Fabric WorldEdit adapter, `r:#worldedit` or `r:#we` uses your current cuboid selection instead of a numeric radius; it cannot be combined with explicit coordinates.

### Pages and ordering

Use the clickable **Previous**, **Next** and **Oldest/Newest first** controls in history results. To type the next page, repeat the same search with `p:2`, then `p:3` and so on. A Next control can lead to an empty page when the preceding page was exactly full.

Inspector pages show at most five transactions. Keyboard alternatives are:

```text
/guardian inspect page 2
/guardian inspect order oldest
```

## Search item transactions

```text
/guardian transactions u:YourName t:1h l:20 p:1
/guardian transactions t:30m x:100 y:64 z:-20
/guardian transactions 100 64 -20
```

User-filtered item history without a region spans recorded dimensions. Adding a position, radius or selection restricts it to the current dimension. Item history accepts the history filters above except `a:`. The older `/guardian transactions player <name-or-uuid>` form remains available for compatibility.

Green entries describe items added to an inventory; red entries describe removals. Records preserve item counts and persistent Data Components, including modded item identities where supported. Opening a container without changing items does not create a transaction.

Hopper history is **off by default**. Enable `logging.automatedContainerTransfers` to record supported block-to-block transfers. Automated records show the observed source and destination coordinates together. A transfer **into a hopper** does not mean the item also reached the barrel below it. Failed pushes into a full destination should produce no transfer record.

## Roll back blocks

**Block rollback changes the world directly; there is no separate block-preview command or general redo command.** Back up the world, check the corresponding lookup and use a small, explicit region first.

```text
/guardian lookup u:YourName t:10m r:3
/guardian rollback u:YourName t:10m r:3
```

Rollback requires `t:<time>`. Without an explicit radius it uses the configured default of 10 around your position or supplied coordinates. You can narrow by player and block action. Work is bounded per tick and by a maximum record count. Unloaded chunks are skipped rather than loaded for you; changed or unverifiable states can be refused. Check the completion summary instead of assuming every matching row was restored.

**Item rollback apply is unavailable**, including for vanilla inventories. Block rollback is not a replacement for restoring a container's item transaction history.

### Read-only item checks

```text
/guardian rollback-items preview u:YourName t:1h r:5
/guardian rollback-items recovery
/guardian rollback-items recovery <operation UUID>
/guardian rollback-items recovery saved <operation UUID>
/guardian rollback-items cancel
```

Preview checks a bounded set of candidate transfers against observed inventories. Recovery lists unfinished internal journals or compares their observed state; `saved` compares supported saved data. These commands **do not restore items**. An eligible preview does not establish that applying it would be safe. Busy, unsupported, missing, unloaded or sealed-loot inventories are skipped or refused. Cancel stops the running observation; it does not clear unresolved recovery claims or undo items.

## Permissions

| Node | Access | Vanilla operator fallback level |
| --- | --- | --- |
| `guardian.lookup` | Block and item history | 2 |
| `guardian.inspect` | Inspector clicks and pages | 2 |
| `guardian.status` | Storage/capture status | 2 |
| `guardian.rollback` | Block rollback and read-only item checks | 3 |
| `guardian.config.edit` | Fzzy Config editing | 3 |
| `guardian.config.admin` | Fzzy Config administrative access | 4 |

Fabric can use Fabric Permissions API and LuckPerms; NeoForge uses its built-in permission handler. When no provider supplies permissions, the normal operator fallback applies. To grant an installed LuckPerms user a node, for example:

```text
/lp user YourName permission set guardian.inspect true
```

Grant lookup separately if that staff member also needs search commands. Guardian rechecks permissions before deferred history output and further block rollback work. Fabric LuckPerms 5.4.140 and NeoForge LuckPerms 5.4.150 passed server-side checks in the tested 1.21.1 setup; this does not certify every provider/version combination.

`/co` is an alias for `/guardian`. The short subcommands are `l` for lookup, `i` for inspect and `rb` for block rollback.

## Configuration

Edit the generated server configuration. Restart after changing any setting marked as requiring a restart; a stopped-server edit followed by startup is the simplest way to apply changes consistently.

| Setting | Default | Purpose |
| --- | --- | --- |
| `general.enabled` | `true` | Enable Guardian's runtime |
| `logging.enabled` | `true` | Master capture switch; history remains available when capture is off |
| `logging.playerBlockChanges` | `true` | Player block capture |
| `logging.containerTransactions` | `true` | Supported item transaction capture |
| `logging.automatedContainerTransfers` | `false` | Supported block-hopper capture |
| `storage.backend` | `SQLITE` | Database backend; use SQLite with standard jars |
| `storage.sqliteFile` | `guardian.sqlite` | Database filename inside the server's Guardian directory |
| `lookup.defaultResults` / `lookup.maxResults` | `10` / `500` | History page defaults and limits |
| `lookup.maxRadius` | `100` | Maximum lookup radius |
| `rollback.defaultRadius` / `rollback.maxRadius` | `10` / `100` | Block rollback region limits |
| `rollback.maxRecords` / `rollback.blocksPerTick` | `5000` / `100` | Maximum rollback size and work per tick |

Item history has a hard maximum of 500 results per page even if the configured lookup limit is higher. Performance settings control the bounded writer queue, batch size and flush interval. Increasing them does not certify production capacity; monitor `/guardian status` and server logs for queue failures or skipped captures.

## Supported behavior and limits

Guardian captures player block placement/breaking, accepted supported block interactions, ordinary player inventory moves, supported block-backed menu changes, cursor returns, standalone drops, offhand swaps, accepted creative slot changes and exact vanilla 2×2/3×3 crafting paths. Optional hopper and Fabric WorldEdit capture are available as described above.

Registered modded blocks/items can appear in history when they use supported hooks. Arbitrary custom menus, extended crafting, capability-only storage and mod mutations that bypass those hooks are not universally supported. General fluid/entity history, economy/faction storage and full CoreProtect parity are outside this release.

## Troubleshooting

- **No history:** verify `/guardian status`, logging switches, player name, time window and dimension. Make a fresh supported change after installation and query it. Changing databases starts separate history.
- **No item transactions:** move an item, rather than just opening a menu. Test a vanilla barrel first. Enable hopper capture explicitly for automated movement.
- **Command or completion missing:** check the permission node/operator level. Inspector access and lookup access are separate grants.
- **Rollback skipped rows:** read its summary; verify the chunks are already loaded and the live blocks still match the recorded chain. Retain backups rather than forcing restoration.
- **Missing dependency or driver:** install the correct server dependencies and the matching loader jar. Standard builds include SQLite only; selecting DuckDB requires a maintainer's explicit DuckDB-enabled build.

For a reproducible issue, include the Guardian/loader/Java versions, exact command or action, coordinates/dimension, expected and actual result, relevant server log and pack/claim mods. Do not share credentials or private player data in a public issue.

## Development and release status

See [development](DEVELOPMENT.md), [architecture](docs/ARCHITECTURE.md), [storage format](docs/STORAGE_SCHEMA.md), [validation](docs/VALIDATION.md) and the [fixed release checklist](docs/RELEASE_SCOPE.md). The changelog follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## License

The core uses the [Bare Minimum License v1.0](LICENSE). The optional Fabric WorldEdit adapter uses [GPL-3.0-or-later](worldedit-adapter/LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md).
