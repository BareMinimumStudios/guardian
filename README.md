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

Guardian is a server-side audit mod for Minecraft 1.21.1 on Fabric and NeoForge. It records supported block changes and item movements, with optional player activity and entity history, so staff can investigate incidents, inspect containers and roll back recorded block edits and supported item transfers. History is stored locally in SQLite.

**Current version: 0.4.0-alpha.88+1.21.1.** This is an alpha for testing in a backed-up world. Automated and server-side permission checks have passed; connected-client acceptance and final release sign-off remain pending. Players do not need Guardian or Fzzy Config on their clients.

## Install or update

Use Java 21 and install the jar matching your **server** loader. Each core jar is about 12.7 MiB and includes SQLite; no separate database mod or common-module jar is needed.

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

Stop the server before copying its database for a simple consistent backup. Startup upgrades existing supported databases to **schema 24**. Keep the pre-upgrade backup: an older Guardian build cannot open a schema newer than it supports. Changing database backends does not convert or move history. Old ExProtect files are not moved automatically.

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
| `u:` or `user:` | Player names; comma lists and `!` exclusions | `u:Alex,Bob`, `u:!Alex` |
| `i:` or `include:` | Include matching block/item IDs in history | `i:stone,example:slate` |
| `e:` or `exclude:` | Exclude matching block/item IDs from history | `e:minecraft:diamond` |
| `t:` or `time:` | How far back to search | `t:30m`, `t:2h`, `t:1d12h` |
| `r:` or `radius:` | Cuboid radius around you or explicit coordinates | `r:10` |
| `x: y: z:` | Explicit block coordinates | `x:100 y:64 z:-20` |
| `m:` or `movement:` | Exact movement UUID from a recorded result | `m:<full UUID>` |
| `c:` or `cause:` | Block-history cause, with comma lists | `c:fire`, `c:explosion`, `c:fire,explosion`, `c:player` |
| `a:` or `action:` | Block action; lookup and block rollback only | `a:place`, `a:break`, `a:change`, `a:place,break` |
| `l:` or `limit:` | Results per history page | `l:20` |
| `p:` or `page:` | History page, starting at 1 | `p:2` |
| `o:` or `order:` | History sort order | `o:oldest`, `o:newest` |

Time units are `s` seconds, `m` minutes, `h` hours, `d` days and `w` weeks. Values must be positive. Configured result/radius limits still apply. With the Fabric WorldEdit adapter, `r:#worldedit` or `r:#we` uses your current cuboid selection instead of a numeric radius; it cannot be combined with explicit coordinates.

### Multiple players and block/item filters

```text
/guardian lookup u:Poke097,cherry_cordial t:1d i:stone,example:slate
/guardian transactions u:!Poke097 t:1d i:coal e:diamond
/guardian chat u:Poke097,cherry_cordial t:1d
/guardian sessions u:!Poke097 t:1w
```

`u:Alex,Bob` matches either player. `u:!Alex` searches everyone except Alex; use `u:Alex,Bob,!Bob` to combine a list with an exclusion. Exclusions win. The user list accepts up to 32 names; names are case-insensitive. Online players resolve to UUIDs, including records under an older name; offline names match the names stored in history.

Block/item include and exclude lists accept up to 64 exact IDs each. An omitted namespace means `minecraft:`. Modded IDs such as `example:slate` work even if the mod is no longer installed; tab completion offers currently registered IDs. Wildcards, tags, and display names are not supported.

A block record matches either its recorded **before or after** block ID. An item transaction matches any recorded nonempty before/after item stack. Including several IDs means any of them can match. Excluding an ID hides the **whole event or transaction**, even if another included item also occurs in it; Guardian does not manufacture a partial transaction for display.

These filters run before result limits and pagination. Player lists also work in command, entity and fluid history. Resource IDs refer to block/item IDs in those histories, entity types in entity history, and fluid/host-block IDs in fluid history; activity history has no resource filter. Block rollback, restore/redo and their previews accept multi-user and block-type filters as described below. Read-only **item** previews still accept one included user and refuse extended history filters.

On first schema-12 startup, Guardian builds an item-ID index from existing transaction payloads in batches. The original history and payload bytes are preserved. A large existing database may take longer to open for this one-time migration; back it up first.

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

User-filtered item history without a region spans recorded dimensions. Adding a position, radius or selection restricts it to the current dimension. Item history accepts the history filters above except `a:`, including block/item ID include and exclude lists. The older `/guardian transactions player <name-or-uuid>` form remains available for compatibility.

Green entries describe items added to an inventory; red entries describe removals. Records preserve item counts and persistent Data Components, including modded item identities where supported. Opening a container without changing items does not create a transaction.

Hopper history is **off by default**. Enable `logging.automatedContainerTransfers` to record supported block-to-block transfers. Automated records show the observed source and destination coordinates together. A transfer **into a hopper** does not mean the item also reached the barrel below it. Failed pushes into a full destination should produce no transfer record.

## Piston and falling-block history

`environment.pistons` and `environment.fallingBlocks` are independent, **off-by-default** settings. Movement history uses unknown attribution; it does not blame the last player to place a piston, power source or falling block.

```text
/guardian lookup c:piston t:1h r:25
/guardian lookup c:gravity i:minecraft:sand t:1h
/guardian lookup m:00000000-0000-0000-0000-000000000000 l:64
```

The last example is a UUID placeholder: use the movement ID from an actual result. Hover a movement row for its full ID and phase; click it to prepare a matching `m:` search. Correlated rows are ordinary block history, so left-click inspection, block/resource filters and paging still work. Searches use the command's normal dimension scope.

Piston capture observes the actual successful structure plan, waits for moving-piston placeholders to disappear, and verifies the affected blocks against the expected settled states. Supported pushes, sticky pulls, slime branches and destroyed targets share an operation ID. Empty strokes, blocked/over-limit moves and denied moves do not claim a transfer. Piston-base power/extension changes are not a separate event. The observer never forces animation completion or loads missing chunks. Interfering, overlapping, unloaded or unverified operations are skipped with a throttled incomplete-history warning. This conservative first checkpoint can skip waterlogged/state-changing moves and custom mod mechanics. At most 128 plans with 64 affected positions each wait up to 20 world ticks.

Falling history distinguishes **began falling** at the source from **landed** at a successfully placed destination. Both use the falling entity's UUID as correlation. A failed landing that drops an item produces no landing row. Canceled/unregistered spawns are not reported as successful departures. Captured landing state is read after the normal landing callback; transformations that no longer match the falling block, such as concrete hydration, are skipped in this checkpoint. Entity teleportation and arbitrary custom falling mechanics are not certified. Entity UUID correlation is not a promise that both endpoints were captured: capture may have been disabled, rejected, purged or interrupted.

All these causes are **read-only for recovery**; public rollback/restore refuses them. Confirmed block-history purge can remove old endpoints independently and retains its existing recovery protection. Correlation IDs group observed facts, not an atomic or guaranteed-complete movement. Queue pressure may leave partial history; check Guardian status and warnings. Schema 22 adds nullable correlation/phase columns without changing old payloads. Config version 16 adds the disabled settings. Back up before upgrading; older builds cannot read the newer schema.

## Growth and leaf decay history

`environment.growth` and `environment.leafDecay` are independent, **off-by-default** server settings. This checkpoint covers ordinary crop random ticks through the standard crop base class, sapling advancement/tree placement, supported player bone-meal use and natural leaf decay. It does not claim every plant, fungal spread or modded growth implementation. Passive cactus/sugar-cane/bamboo growth is outside the random-tick hooks in this checkpoint unless it follows a separately supported bone-meal path.

```text
/guardian lookup c:growth t:1h r:25
/guardian lookup c:growth u:YourName t:1h
/guardian lookup c:decay i:minecraft:oak_leaves t:1d
/guardian lookup c:growth,decay l:20 p:2
```

Crop summaries show recorded age changes; sapling/tree changes and decayed leaves appear in ordinary block inspection. Natural ticks have unknown attribution. Direct supported bone-meal use records the player, including nested tree growth; it does not assign later natural growth to whoever planted or fertilized a block earlier. No planting-owner database is created.

Whole tree and bone-meal calls are recorded as net before/after changes. Failed tree attempts, mature-crop no-ops and persistent leaves do not invent growth/decay. NeoForge environmental item history waits until the loader accepts its staged item use; denied bone-meal and ignition changes that are restored leave no event. Actual pack/claim and connected-client acceptance remains pending.

Growth/decay history is **read-only for recovery**: public block rollback/restore excludes these causes and refuses explicit requests for them. Confirmed block-history purge includes these records with the existing safeguards. Capture settings affect future events only; disabling capture leaves old records searchable. Trees can create many records, so monitor queue/backpressure status. Back up before schema 21: the new marker protects the added cause codes from older builds; existing payloads are unchanged by that migration. Config version 15 adds the two disabled settings.

## Fire and explosion history

Two independent server settings, `environment.fire` and `environment.explosions`, are **off by default**. Enable the categories you need. Fire ticks and large explosions can create substantial history; existing queue limits apply, and rejected records produce a throttled warning. Neither setting changes fire ticks, mob-griefing rules or claim protection.

```text
/guardian lookup c:fire t:1h r:25
/guardian lookup c:explosion u:YourName t:1d
/guardian lookup c:fire,explosion i:minecraft:oak_planks t:1h
```

Fire capture records supported flint-and-steel/fire-charge ignition, scheduled spread/burning and actual transitions to or from fire/soul fire, including extinguishing. Routine fire age/shape updates, unchanged attempts and transient fire that immediately disappears without a net change are omitted. Direct ignition can identify the player; later spread/burning and untracked extinguishing show unknown rather than a guessed original owner. Lit campfire/candle changes through the supported ignition tools are included.

Explosion capture observes actual block changes during standard explosion finalization, including supported TNT, creeper and other ordinary explosion paths. A known indirect player source is used, otherwise the known source entity or unknown. A nearby player is never treated as the cause. Explosions prevented before mutation, mob explosions with block griefing disabled and unchanged targets do not create destruction records. Custom mod implementations that bypass these methods need compatibility testing. TNT priming/removal before an explosion and entity/drop damage are not separate events in this category.

These rows appear in ordinary block lookup and left-click inspection, retain their cause across restart and participate in confirmed block-history purge. **Fire/explosion recovery is read-only in this checkpoint:** public block rollback/restore excludes these causes, and explicitly requesting them returns an explanation. Recreating blocks alone could duplicate drops or reintroduce hazardous fire/TNT state. This feature does not restore entities, inventories or dropped items.

`c:` filters apply to block history; item, entity, fluid and activity commands reject them. Capture settings affect future events only. Installing an update does not reconstruct earlier fires or explosions.

## Chat and login/logout history

Guardian can record **ordinary public chat** and **login/logout events**, including player UUID, the name recorded at the time and an event timestamp. These are event records, not a session replay or a calculated play-time report.

- `activity.chat` defaults to **false**. Enable it explicitly in the server configuration if you want public chat stored.
- `activity.sessions` defaults to **true**. Disable it to stop recording login/logout events.
- `general.enabled` and `logging.enabled` also govern this capture. Disabling capture does not delete previously stored history.

```text
/guardian chat u:Poke097 t:1d l:20 p:1
/guardian sessions u:Poke097 t:1w l:20 p:1
```

Replace the example name with the player you want to investigate. These searches accept `u:`/`user:`, `t:`/`time:`, `l:`/`limit:`, `p:`/`page:` and `o:`/`order:`. Leave the user filter out to search all recorded players. Results span the server and have clickable paging/order controls. User lists and `!` exclusions work here too. These commands do not accept resource, radius, coordinate or block-action filters.

Chat searches require **`guardian.chat`** and session searches require **`guardian.sessions`**, each with vanilla operator level **3** fallback. Ordinary `guardian.lookup` permission does not grant either. Restrict access and communicate your server's logging policy to players.

The chat record contains plain decorated public text at the broadcast boundary, capped at 4,096 characters. Commands, `/msg`/private-message chat types, system messages and fully filtered messages are excluded. This does not promise capture of arbitrary custom chat channels from other mods. Login/logout capture starts after Guardian is running; it cannot reconstruct earlier sessions. Orderly shutdown records currently connected players leaving before storage closes. A crash may leave a login with no logout.

Startup upgrades supported databases to schema 15 without replacing existing block/item history. Back up before upgrading. This milestone passed synthetic server checks; actual network login, cancellation by pack mods and connected-client presentation still need acceptance on your pack.

## Player command history

Command capture is **off by default**. Enable `activity.commands` in the server configuration to record player-attributed commands after Minecraft accepts their syntax. This is an admission record, **not proof that the command succeeded**. Unknown commands, syntax failures and cancellations before the capture boundary are not recorded. Console commands are excluded; this is not a command-block or function trace.

```text
/guardian commands u:Poke097 t:1h l:20 p:1
/guardian commands u:Alex,Bob,!Charlie t:1d o:oldest
```

`/co commands` also works. Searches use the same user/time/limit/page/order filters and clickable navigation as chat history. They require the separate **`guardian.commands`** permission, with operator level **3** fallback. Ordinary lookup, chat or session grants do not grant command-history access. Capture and history access are independent: disabling capture leaves existing records searchable by authorized staff.

By default only the command root is kept, such as `/give`; its arguments are not stored. Set `activity.commandArguments` to `true` only if you also want arguments recorded. Arguments can contain sensitive text, including values from custom mod commands. Review your command handlers and exclusions before enabling them. Changing this setting affects future records, not existing history.

Common authentication, password, private-message, account-link and Guardian commands are always excluded, including their namespaced forms. This keeps `/login`, `/register`, `/msg`, `/tell`, `/reply`, team messages and Guardian confirmation/search commands out of command history. It does not promise recognition of every custom authentication alias. Add custom roots to `activity.commandExcludedRoots`, for example `signin,myplugin:accountlink`. A bare root excludes its namespaced variants; a namespaced entry excludes that exact root. Up to 64 comma-separated ASCII roots are accepted; malformed/oversized exclusion settings fail closed and stop command capture until corrected.

Nested wrappers such as `execute`, `function`, `schedule`, `return`, `sudo` and `runas` retain only their roots even when argument capture is enabled. Payloads are capped at 4,096 characters; oversized argument text falls back to the root rather than a partial argument. Inputs above 32,768 characters or with control/hidden formatting characters are skipped. Public chat, sessions and commands have independent capture switches under the shared `general.enabled` and `logging.enabled` master switches. Existing bounded writer/backpressure reporting applies. Pack-specific command rewriting/cancellation and connected-client presentation still require acceptance on your pack.

## Entity history

Entity capture is **off by default**. Enable `entities.deaths` and/or `entities.nameChanges` in the server configuration. Busy mob farms can generate many records; monitor the writer queue and database size before enabling death capture across a large server.

```text
/guardian entities a:death t:1h r:25
/guardian entities a:rename u:YourName t:1d
/guardian entities i:minecraft:villager t:7d l:20 p:1
/guardian entities id:<entity-UUID>
```

`/co entities` is also available. The separate `guardian.entities` permission uses operator level **2** fallback. Filters include `u:`, `t:`, `r:`, coordinates, `i:`/`e:` entity type IDs, `l:`, `p:` and `o:`. Use `a:death`, `a:rename`, `a:tame`, `a:breed`, `a:frame` or a comma-separated combination; `id:` selects the target entity UUID. User filters select the attributed actor, not the victim or target. For example, a player filter finds deaths attributed to that player's damage source. A radius searches your current dimension; searches without a spatial filter cover all recorded dimensions. Entity types from mods can match when their events pass through the supported hooks.

Results show the entity type, recorded custom name and location. Hover for the entity UUID, dimension, timestamp and damage type; click to prepare a search for that entity. Death capture covers the standard living-entity and server-player death methods. It uses the damage source's entity for attribution, or `environment` when none is supplied. It does not infer a previous attacker or attribute `/kill` to the person who typed it. The tested NeoForge cancelled-death path creates no record.

Name history records changes made through the standard custom-name setter for tracked entities. Standard name-tag use records the player; other setters show an unknown actor when no supported attribution context exists. Names are plain text limited to 256 characters. Formatting-only changes and changes that differ only beyond that limit are not recorded. Initial names during construction/loading, repeated identical names and chunk reloads do not create rename records.

### Taming and breeding

Enable `entities.taming` and/or `entities.breeding` to capture these events. Both are **off by default** and use the same `guardian.entities` permission and search filters.

```text
/guardian entities a:tame u:YourName t:1d
/guardian entities a:breed i:minecraft:pig t:1h r:25
```

Taming records a successful untamed-to-tamed transition through the standard tameable-animal or horse tame method, with the verified owner as the actor. Repeated calls on an already-tamed animal do not create extra records. Failed bone attempts, direct owner-UUID edits and later ownership transfers are not first-time tame records. Animals that were already tamed before capture was enabled are not backfilled.

Breeding records the child after the standard animal breeding method successfully inserts it into the server level. The target UUID is the **offspring**, and both parent UUIDs appear on hover. Credit follows Minecraft's available breeding player: the first parent's love cause, or the partner's if the first is unavailable. A missing player attribution is shown as unknown; it is not guessed from nearby players. Both parents may have been fed by different players, but this record has one credited actor.

Cancelled NeoForge baby-spawn events and refused child insertion produce no birth record in the tested paths. A child that is removed later is a separate event, not a failed birth. Egg-laying, pregnancy, villager breeding and custom reproduction/taming methods that bypass these hooks are not covered. This feature does not change breeding or taming gameplay.

This is read-only history. It does not record every entity property, general spawn/despawn or entity inventory, and it cannot restore entities. Mods that replace these methods or bypass the setter need separate compatibility testing. Confirmed history purge also includes old entity records. Capture changes apply to future events only.

### Item frames

Enable `entities.itemFrames` to record normal and glow item frames. It is **off by default**. Frames are entities, so their records appear in entity history rather than block lookup or container transactions.

```text
/guardian entities a:frame t:1h r:5
/guardian entities a:frame-remove u:YourName t:1d
/guardian entities a:frame i:minecraft:glow_item_frame t:1d
```

`a:frame` includes placement, destructive removal and display changes. Use `a:frame-place`, `a:frame-remove` or `a:frame-change` to narrow the search. Entity type filters match the frame type, not its displayed item. Target UUID searches distinguish frames even when they are nearby or a replacement occupies the same position.

Right-clicking an empty frame with an item records insertion. Right-clicking an occupied frame records rotation. A normal attack on an occupied frame records item removal; the next attack can record the empty frame breaking. Destructive removal with an item still present retains that display snapshot. Standard persistent Data Components are captured with the existing immutable item codec, including named and modded items; nonpersistent or oversized components can be refused and reported in the log. Text summaries show item registry identities, not a full component dump.

To inspect a live frame, enable `/guardian inspect` and click the frame. Both `guardian.inspect` and `guardian.entities` are required. Frame inspection consumes attack and interaction packets without removing, adding or rotating the item; paging stays tied to that frame UUID. Turn inspection off before changing the frame. If the frame is already gone, use `/guardian entities` with time, position, actor or UUID filters.

Chunk unloads, dimension-transfer removals, repeated damage on a removed frame, unchanged interactions and tested fixed-frame refusals do not create destruction/change records. Damage attribution uses the supplied damage-source entity; a destructive removal without that context is recorded as `world`, not guessed from nearby players or the support block's history. Initial loaded frames and arbitrary direct item/rotation edits are not backfilled or universally captured. Failed placement and protection-mod cancellation before the relevant hook produce no accepted placement; actual pack protection and rendered client behavior still need connected-client acceptance.

Frame history is read-only. It does not restore frames or transfer items, and old frame records are included in confirmed history purge. Existing frame history remains searchable when capture is disabled.

## Fluid bucket history

Fluid capture is **off by default**. Enable `fluids.bucketChanges` in the server configuration, then restart. The separate `guardian.fluids` permission uses operator level **2** fallback.

```text
/guardian fluids u:YourName t:1h r:25
/guardian fluids a:place i:minecraft:water t:1d
/guardian fluids a:remove i:minecraft:lava t:1d
```

`/co fluids` also works. Use `a:place`, `a:remove` or `a:place,remove`, with the normal user/time/radius/coordinate/resource/limit/page/order filters and clickable navigation. `i:` and `e:` match recorded fluid registry IDs or host block IDs at either end of the change, not bucket items. A spatial search uses your current dimension; without a spatial filter it covers recorded dimensions. Hover a result for the dimension, exact timestamp, source flags and before/after host block IDs.

This records persistent changes through the standard player bucket placement and pickup paths, including supported waterlogging. It uses the actual changed target when a bucket falls back to an adjacent block. Accepted no-ops and water vaporizing in the Nether produce no placement record. Registered mod fluids can appear when they use these hooks; custom bucket implementations need compatibility testing.

Bucket capture alone does not cover natural spread or fluid reactions; enable the separate flow setting below for supported natural paths. Pumps, dispensers, cauldrons, powder snow, milk and custom tank/storage APIs are outside these bucket hooks. Fish-bucket water can pass through these hooks; the released creature is not a fluid event. Claim cancellation before the hooked operation creates no observed change, but actual pack protection and client prediction remain part of connected-client acceptance.

### Natural flow and fluid-formed blocks

`fluids.naturalFlow` is a separate **off-by-default** setting. Enable it to record supported scheduled fluid spread, level changes and recession, plus standard liquid interactions forming stone, cobblestone, obsidian and basalt. These can generate substantially more history than bucket capture. Existing queue limits and backpressure still apply; check `/guardian status`.

```text
/guardian fluids a:flow t:10m r:20
/guardian fluids a:form t:1h
/guardian fluids i:minecraft:obsidian t:1d
```

Hover for before/after fluid IDs, source flags, fluid amounts and host blocks. Bucket rows retain `a:place` and `a:remove`; flow is not described as another bucket placement. Old rows have unknown amounts because they were not previously stored.

Source attribution is conservative and temporary: successful supported player bucket placements seed a bounded trace even if bucket-history capture is disabled. Traces expire after 1,200 world ticks (about one minute at 20 TPS), source/chunk changes, eviction or restart. Competing or untracked feeding fluids show `environment/unknown`; user filters cannot match those unknown records. Capture never loads missing chunks to recover attribution. It does not reconstruct earlier sources, and direct chunk edits or custom mod flow implementations need separate compatibility checks. This is not complete fluid simulation or player attribution for every flowing liquid. Flow/formation history participates in the existing confirmed purge.

Fluid history is **read-only**. It has separate records and does not expand block rollback/restore or item rollback. Confirmed history purge includes old fluid records. Disabling capture leaves old fluid records available to authorized staff; enabling it does not reconstruct earlier changes.

## Roll back blocks

**Applying block rollback changes the world directly.** Back up the world, run a preview and use a small, explicit region first. Use block restore/redo below to reapply previously rolled-back history.

```text
/guardian lookup u:YourName t:10m r:3
/guardian rollback preview u:YourName t:10m r:3
/guardian rollback u:YourName t:10m r:3
```

### Preview before applying

```text
/guardian rollback preview t:1h r:5
/guardian rb preview u:Poke097 t:30m r:3
```

Block preview is **read-only**: it does not place/remove blocks, claim history rows, change rollback flags or load missing chunks. It uses the same `guardian.rollback` permission and bounded record/tick limits as apply. Preview and apply cannot run simultaneously through Guardian's block rollback service.

The summary distinguishes rows that would restore, rows already in their before state, state mismatches, unloaded chunks, blocked older chains, unsupported targets and read failures. Up to five example changes show coordinates and before/after IDs. Counts describe **history rows**, so several rows may refer to the same block.

Guardian simulates the selected newest-first chain; it does not just count lookup results. This is a check of the recorded and observed state, not a promise that a future apply will succeed. Neighbor updates, mod behavior and later player/world changes can alter the outcome. Preview creates no reservation or confirmation token. Applying the same filters runs a fresh query and revalidates live state; your position, dimension and moving time window also matter.

Both preview and apply accept the same multi-user and block-type filters described below. Block preview does not restore items. Block restore/redo has its own preview and permission below.

Rollback requires `t:<time>`. Without an explicit radius it uses the configured default of 10 around your position or supplied coordinates. You can narrow by block action, up to 32 comma-separated player names and exact block IDs. Use `u:Alice,Bob` to include either player, or `u:Alice,Bob,!Bob` to exclude Bob. An exclusion takes precedence; `u:!Bob` includes everyone except Bob, including unknown actors. Online names resolve to their current UUID; offline names match names recorded in history.

`i:minecraft:stone,example:ore` includes a row if either recorded block state has a listed ID. `e:minecraft:dirt` excludes a row if either state has that ID, even if the other state matches an include. Each type filter accepts up to 64 IDs. These are block IDs, not item contents; `i:minecraft:air` can match many placement/break records. Wildcards are not supported. The same filters work with `/co`, restore and redo:

```text
/guardian rollback preview u:Alice,Bob t:1h r:5 i:minecraft:stone
/guardian rollback u:Alice,Bob t:1h r:5 i:minecraft:stone
/guardian restore preview u:Alice,Bob t:1h r:5 i:minecraft:stone
/guardian restore u:Alice,Bob t:1h r:5 i:minecraft:stone
```

Filters select **history rows**, not a promise to restore every matching block. They can leave gaps between changes at the same position. Existing live-state checks still apply and a mismatch blocks the remaining selected chain at that position. Review skipped/blocked counts. Preview and apply run separate queries with their own moving time windows and do not reserve a fixed batch. Rollback remains newest first; restore remains oldest first, using the IDs in the original recorded before/after states. The record cap applies after filtering. Work is bounded per tick and by a maximum record count. Unloaded chunks are skipped rather than loaded for you; changed or unverifiable states can be refused. Check the completion summary instead of assuming every matching row was restored.

Online item transaction rollback is available for the bounded supported inventories below. Block rollback is not a replacement for restoring a container's item transaction history.

### Restore a block rollback (redo)

Restore reapplies the original changes that Guardian previously marked as rolled back. It is a filtered history operation, not an automatic undo of your last command. Use the same player, location and action filters as the rollback you intend to reverse. `t:` refers to the **original event time**, so use a long enough window to include those changes.

```text
/guardian restore preview u:YourName t:1h r:3
/guardian restore u:YourName t:1h r:3
/guardian redo preview u:YourName t:1h r:3
```

`redo` is an alias for `restore`; both also work under `/co`. Preview is read-only, and applying restore is a separate command. Back up the world and inspect the preview first. Restore uses `guardian.restore` (operator level 3 by default), separately from `guardian.rollback`.

Restore replays only rolled-back or restore-pending rows, oldest first. The live block must match the recorded before state; Guardian then reproduces the recorded after state. Conflicts stop later selected changes at that position. Unloaded chunks are skipped; unsupported or unverifiable targets are refused. A block already matching its target can be reconciled without another write. The same record/tick/radius limits and shared busy lock apply to rollback and restore. Both accept multiple included/excluded users and included/excluded block IDs, and refuse paging/limit parameters. Their replay order is fixed by the operation.

Successful restore returns the original history rows to active state without adding replacement audit records. A later rollback can reverse them again. Interrupted restore work has a distinct `restore pending` label and is excluded from ordinary rollback; retry restore with the original filters to reconcile matching live states. Unresolved conflicts stay pending. Preview reserves nothing, and database journal completion does not guarantee the matching chunk save survived an abrupt process or machine failure. Inspect uncertain outcomes before retrying; keep backups. Restore follows the existing block snapshot support, including recorded block-entity data; it is not item-transaction rollback and cannot reconstruct unrecorded changes.

### Read-only item transaction viewer

```text
/guardian transactions ui u:YourName t:1h l:20
/guardian transactions ui x:133 y:67 z:-3366 l:10
/guardian transactions ui i:minecraft:coal t:1h
```

The viewer opens an ordinary chest-style menu on **Fabric and NeoForge**, with no client mod,
resource pack, InventoryEx, SGUI or Polymer dependency. It requires `guardian.lookup`
(operator level 2 fallback). `/co transactions ui` is an alias. Existing user, time, position,
radius, item include/exclude, order and page filters apply. `l:` is limited to 50 transactions
per result page and the configured history maximum. Console users can use text history.

**Before is on the left; after is on the right.** Each aligned pair represents one recorded
changed slot. Hover a card for the exact recorded count, item ID, owner and slot number.
The book shows player/system, UTC timestamp, action and transaction UUID. Player-owned slots
use short labels such as “Your inventory”; the full UUID remains in the tooltip. All item cards are
plain display icons: historical names, lore, nested contents and custom components are not
decoded or sent to the client. The history keeps those original component bytes, and the UI
marks a saved-component change. Modded/unknown item IDs use paper icons with the recorded ID
and count in their tooltip. Icon stacks are display markers; read the named recorded count.

Left-click the bottom buttons for previous/next result page, transaction, or changed-slot
page. Each changed-slot page shows 16 before/after pairs. Gray buttons mark unavailable
directions; loading pages temporarily hide old results and disable navigation. An empty page
says that no records match and still allows returning to the previous result page. The barrier
closes the viewer. **Every slot is read-only, including the shown
copy of your own inventory.** Close the viewer before rearranging your items. Shift-click,
dragging, hotbar swaps, double-click, drops and creative clone clicks cannot move display items.
There is no rollback button in this UI. These are transaction changes, not a complete
historical inventory backup.

Container inspection also offers `[Open item UI]`. With inspect enabled, right-click the
container and click that link or run `/guardian inspect ui`. This uses the selected container's
dimension and requires both inspect and lookup permission. Disabling inspection or losing
permission closes that inspector viewer. Ordinary text inspection remains available.

Close other menus and clear your cursor first. The first UI implementation supports
non-spectator players; vanilla spectator chest menus do not send navigation clicks. It refuses
oversized result pages (over 4,096 changed slots or 8 MiB of recorded snapshot bytes); narrow
the search if needed. Each result page reads current history, so concurrent new records or
purge can change later search pages. Pending results cannot reopen a closed/replaced menu,
and current permission is checked again before showing results and while browsing.

### Online item transaction rollback

Use item rollback to reverse a **recorded transfer**, including both its source and destination.

If preview reports an unavailable inventory, alpha.90 adds a detail line naming the player or
the container's dimension and coordinates, followed by the failed check. This distinguishes
an open menu, cursor/crafting slot, unsupported inventory implementation, unloaded container
or sealed loot. A late change during checking is reported separately. Copy these detail
lines when reporting a failure; the summary alone combines several different conditions.
At most eight owners are detailed per response. Preview remains read-only, and a more
specific message does not make an unsupported inventory eligible.
It is not an inventory backup command and does not recreate items that have since been used,
dropped, crafted or moved through unsupported/unrecorded actions. Start with a small recent
shift-click transfer between your inventory and a nearby barrel.

```text
/guardian rollback-items preview u:YourName t:10m r:5
/guardian rollback-items apply u:YourName t:10m r:5
/guardian rollback-items confirm <token returned by apply>
```

`preview` changes nothing. `apply` selects history and returns a requester-bound token that
expires after 30 seconds; **selection does not change items**. Close all players' open menus
before confirming. Confirmation rechecks current permissions, history, source claims, every
endpoint and the complete live inventory images. Keep every block endpoint inside the chosen
region and loaded, and keep affected players online with idle inventory menus. One included
`u:`/`user:` filter is supported; item rollback does not accept multi-user or item-type filters.

The first online implementation supports exact vanilla barrels, chests, hoppers, dispensers,
droppers, furnaces, blast furnaces and smokers, plus the ordinary 41-slot online player
inventory. It refuses sealed loot, missing/unloaded blocks, custom inventory subclasses,
entity/capability storage, temporary cursor/crafting slots and nonconserving transactions.
That makes completed shift-click transfers the most useful first test. A normally clicked
transfer may include a temporary cursor owner and be refused. Registered item components are
preserved through the same immutable snapshot codec used by history; transient components
that cannot be saved losslessly are refused.

Requests are limited to 50 selected transactions, 8 inventories, 64 changed final slots and
2 MiB of combined complete before/after images. Before item writes, Guardian saves and verifies
each original inventory and checks its maintenance recovery representation. Expected affected
chunks must fit within 512 KiB of uncompressed saved NBT; external `.mcc` chunks are refused.
Narrow `t:` or `r:` if a request is too large.
No inventory is overwritten when its observed contents conflict with the recorded transfer.
Already claimed source transactions cannot be rolled back twice.

While applying and verifying saves, Guardian temporarily pauses guarded inventory/gameplay
mutation paths across the server. The server stays online, but inventory interaction and
automation may briefly be unavailable. Native backing lists are detached so retained stack
references cannot alter the owned image. Supported vanilla caller guards prevent unpaid
transfer side effects; arbitrary mod callers, reflective field writes and custom storage
models are not certified by a vanilla class check.

Success is reported only after actual saved owner images and the durable journal receipt
match the expected result. **There is no item redo command.** Both `guardian.rollback` and
`guardian.rollback.items` are required, each with operator level 3 fallback.

```text
/guardian rollback-items cancel
/guardian rollback-items reconcile
/guardian rollback-items recovery
/guardian rollback-items recovery <operation UUID>
/guardian rollback-items recovery saved <operation UUID>
```

`cancel` stops your pending request or requests a stop of your active operation. It does not
undo started writes or saves. Interrupted apply retains inventory protection and its journal.
For an operation retained in the current process, `reconcile` can release protection only
when the complete live and actual saved images already prove the expected result. It does
not retry partial writes or force conflicting contents into place. The `recovery` and `saved`
forms remain read-only diagnostics and do not release claims.

**Restart protection:** an unresolved protected item journal stops normal server startup;
gameplay must not resume with an uncertain cross-inventory save. Preserve `guardian.sqlite`
and the matching world/player data together. Alpha.85 adds [confirmed maintenance recovery](docs/ITEM_RECOVERY.md)
to finish a protected partial rollback using its recorded inventory images. Stop gameplay,
start once with `-Dguardian.itemRecovery=preview:<operation UUID>`, then use the exact
`apply:<operation UUID>:<token>` JVM flag printed by that preview. Both runs exit without
starting gameplay. Recovery stages targeted player/region files, retains before/after copies
and supports retry after interruption; it refuses conflicting files or inventory contents.
Remove the flag before normal startup. A protected operation that never began item writes
can instead be cancelled safely in maintenance. There is no automatic partial replay or force
overwrite. Do not delete journal rows to bypass protection. An unprotected admission proven
not to have begun writing may be cancelled automatically at startup. Keep ordinary backups
and use a backed-up isolated world for initial testing.

## Direct item displays and ground pickups

Item history now records accepted direct insertion/removal from block entities using Minecraft's inventory contract, as well as the optional display adapters below. Keep `logging.containerTransactions = true` to capture future actions.

- Supplementaries notice-board books and other displays using that inventory contract.
- Amendments hanging-sign items, with separate front/back slots.
- Vinery/Foundation bottle racks and Bakery/Farm & Charm display storage.
- Items actually picked up from the ground, including partial pickups when only part of the stack fits. Full inventories, pickup delays and denied/no-op attempts produce no successful pickup row.

Use `/guardian transactions u:YourName t:10m`, or right-click a supported display with inspection enabled. Left-click a sign for its text, colour, glow and wax history; right-click its item display for item history. No compatibility mod is required to run Guardian. Other mods with custom storage need their own verified adapter; this does not promise every modded display or shop is covered.

Direct display actions and ground pickups are history-only. Item rollback does not restore dropped item entities or write to these custom display inventories. Ordinary supported menu transfers retain their existing rollback rules. The item UI's previous/next transaction buttons continue across result pages; changed-slot buttons only navigate within one transaction.

## Sign history

Set `logging.signTextChanges = true` in the server configuration to capture future accepted player text edits, dye/glow changes and waxing or supported wax removal. It is off by default and follows the general and logging master switches. Supported standing, wall and hanging signs share this path, on both sides. Existing text is not imported merely by enabling capture.

```text
/guardian lookup c:sign t:1h r:10
/guardian lookup c:sign u:YourName i:minecraft:oak_sign t:1d
```

Use ordinary **left-click inspection** for sign history. Lookup and inspection retain their existing permissions. Each row identifies text edits, colour, glow and wax changes. Supplementaries signposts identify the upper/lower panel. Hover the row for numbered before/after lines, shown as plain text using the stored filtered view where available. Sign click commands and formatting are never turned into chat actions. Hover lines are limited to 256 characters. Raw and filtered sign components remain in the database snapshots, so treat database access and backups as sensitive.

No-op edits, waxed signs and edits by a player who is not the allowed editor create no successful edit record. Capture observes the final result of the supported editing method and does not grant editing rights, open a sign editor or bypass claim protection. Vanilla dye, glow-ink and ink-sac changes are observed after block use; Supplementaries 3.9.9 signposts/notice-board text and Amendments 2.1.10 hanging-sign displays have optional compatibility paths. Command/NBT edits and unsupported custom edit paths are outside this capture. Packet filtering and your pack's claims still need connected-client acceptance. Text exceeding 1,024 plain characters per line, or a compressed snapshot over 1 MiB, is skipped with a throttled incomplete-history warning. Logging failures do not cancel gameplay.

Sign-text history is **read-only for recovery**: public rollback/restore excludes sign-edit rows and refuses `c:sign`. Ordinary sign placement/break rows keep their existing block behavior. Protected, confirmed block-history purge includes old sign-edit rows and accepts their block IDs. Schema 24 adds an empty compatibility marker for direct item actions; existing history and payloads are preserved. Config version 17 adds the disabled setting. Back up before upgrading; older builds refuse the new schema.

## Export filtered history

Export writes a **server-side UTF-8 JSON Lines (`.jsonl`) file** under `guardian/exports/`. It does not change history, blocks, inventories or journal flags, and does not send a download to the client. A server administrator retrieves the file from disk.

```text
/guardian export blocks t:1h u:Alice,Bob i:minecraft:stone l:100
/guardian export items t:1d u:YourName l:100
/guardian export chat t:1d u:YourName l:100
/guardian export sessions t:7d l:500
/guardian export commands t:1d l:100
/guardian export entities t:1d a:frame l:100
/guardian export fluids t:1h a:flow l:100
/guardian export cancel
```

`/co export` also works. Every export requires **`guardian.export`**, with operator level 4 as fallback, **plus the category's existing history permission**. Export alone does not grant access to chat, commands or other histories. Each command requires `t:` and accepts the category's normal history filters. `l:` defaults to 100 and cannot exceed 500 or the configured history result limit. `o:oldest`/`o:newest` controls ordering. Pages are refused; this is a bounded extract, not a paginated backup.

Block, entity and fluid exports use the command's dimension and optional position/radius or WorldEdit selection. Item exports without a location filter include inventory-only transactions and all worlds. Adding a location filter selects transactions with a matching container endpoint; the **whole transaction**, including its other endpoints and changed slots, is exported. Activity exports are global and accept only user, time, limit and order filters because those rows do not store a world location.

The first JSON object records format version, category, requester, raw filters, resolved scope, time cutoff, row count, limit and `mayHaveMore`. Subsequent lines are structured records. When the limit is reached, more rows may match; no exact total is claimed. Each command runs one query with a fixed lower time cutoff, then writes the returned immutable rows. Logging continues, and separate exports are not one shared point-in-time database snapshot.

Exports preserve stored text, identities, locations, transaction changes, journal labels, correlations and available entity/fluid details. Native block-entity/item snapshots are included as Base64; they are not decoded or executed by export. **These files can contain raw sign components, private history and stored command arguments.** Existing capture redaction remains in effect, but the export is not a public or filtered-chat view. Protect the directory and backups, and grant export permission carefully.

One export runs at a time. Normal cancellation, permission loss or shutdown before publication removes its partial file. Completed files remain on disk after cancellation or permission loss; no command can retract a file already copied elsewhere. Files have generated names, no caller-supplied path and no replacement of existing files. There is no automatic cleanup. The folder accepts fewer than 64 existing entries before a new export; move or remove old files manually when it fills. A crash or storage failure may leave a `.part` file for the administrator to inspect after stopping the server.

Each file is limited to 16 MiB, with at most 8 MiB of raw snapshot payloads total and 4 MiB in one record. Oversized selections fail rather than publishing a truncated file; narrow the filters or lower `l:`. This format has no import/restore command, does not contain a complete database backup and does not establish world-save durability. Keep normal server/database backups.

## Purge old history

Purge permanently removes old **history records**. With only an age filter, it covers all users and worlds; optional filters narrow that scope. It does not change blocks or inventories. Deleted history is no longer available for lookup, rollback or restore. Stop the server and back up the database before your first purge; keep the backup separately.

```text
/guardian purge preview t:30d
/guardian purge preview t:30d w:minecraft:overworld
/guardian purge preview t:30d w:minecraft:overworld i:minecraft:stone,minecraft:coal e:example:protected_item
/guardian purge confirm <token from preview>
/guardian purge cancel
```

`/co purge` also works. Purge requires its own `guardian.purge` permission, with **operator level 4** as the fallback. It requires one age filter (`t:` or `time:`), retaining at least one day. Optional `w:`/`world:` selects one exact dimension ID, such as `minecraft:overworld`, rather than a save-folder name. `i:`/`include:` and `e:`/`exclude:` each accept up to 64 comma-separated exact block/item registry IDs. IDs without a namespace use `minecraft:`; wildcards are not supported. User, radius, cause, movement and page filters are refused. Duplicate filters are refused, including aliases. The cutoff is fixed when preview starts; only records strictly older than that UTC timestamp qualify.

**Read the scope in preview before confirming.** A world filter includes located block/item/entity/fluid history and retains chat, session and command history because those rows have no stored world. Inventory-only item transactions with no block-container location are retained by a world filter. Adding either type filter limits selection to **block history and item transactions**; entity/frame, fluid and activity history are retained. A type matches either recorded block state or any changed before/after item ID. Include means any listed ID; an excluded ID anywhere in the record vetoes the whole record. Nested item contents/components are not searched.

**Item transactions are indivisible.** A matching world or item endpoint selects the entire transaction, including its other item types, inventory slots and container endpoints in other worlds. The world and type matches can come from different endpoints in that transaction. Purge never deletes only part of a transaction. Preview states this behavior, counts each whole transaction once and freezes the exact scope with the confirmation token. Tab completion offers loaded dimension IDs and registered block/item IDs; valid historical IDs can also be entered manually.

Preview changes nothing. It shows eligible block, item-transaction, activity (chat/login/logout/command), entity/frame and fluid counts, the protected count and a fixed batch of at most **1,000 records total**. One item transaction counts as one record, regardless of its slot count. Confirm with the complete token within 60 seconds using the same player or console authority. A new preview replaces the previous confirmation. Tokens are single-use and are not saved across restart. Records inserted after preview are outside that batch, even if they carry older timestamps. Run another preview and confirm to remove the next batch.

Pending block rollback/restore rows and the history at their positions are retained. If any internal item journal, claim, saved image or recovery-protection data remains, Guardian conservatively retains all block/item history; activity, entity/frame and fluid history can still be purged because they do not participate in recovery. Recovery journals are never deleted by purge. Do not edit their tables to bypass protection.

Purge rechecks protection before deleting and removes item-history indexes in the same transaction. Deleting only activity, entity/frame or fluid history does not advance the item-history completeness marker. A confirmed block/item deletion, including a scoped purge, writes a conservative global history-gap marker that makes read-only item checks refuse selected history older than the purged cutoff with `incomplete history`, even when some older records remain protected. Lookups still show retained records. Purge shares a busy gate with block rollback/restore and item checks, and runs database work off the game thread. Logging continues; monitor storage/backpressure status as usual.

Cancel discards a pending confirmation or requests cancellation of running work. Permission loss also cancels pending work. **Cancellation cannot undo a batch that already committed.** Check the reported outcome and history before trying again. Purge does not run VACUUM, remove mapping tables, reset identities or promise an immediate reduction in database-file size; freed pages can be reused by later writes.

## Permissions

| Node | Access | Vanilla operator fallback level |
| --- | --- | --- |
| `guardian.lookup` | Block and item history | 2 |
| `guardian.inspect` | Inspector clicks and pages | 2 |
| `guardian.chat` | Public chat history | 3 |
| `guardian.sessions` | Login/logout history | 3 |
| `guardian.commands` | Parsed player command history | 3 |
| `guardian.entities` | Entity and frame history; also required for frame inspection | 2 |
| `guardian.fluids` | Player bucket fluid history | 2 |
| `guardian.status` | Storage/capture status | 2 |
| `guardian.rollback` | Block rollback, item checks and required base grant for item apply | 3 |
| `guardian.rollback.items` | Confirmed online item rollback and retained-operation reconciliation | 3 |
| `guardian.restore` | Block restore/redo and its preview | 3 |
| `guardian.export` | Write bounded history files; also needs the category history grant | 4 |
| `guardian.purge` | Preview/confirm/cancel old-history deletion | 4 |
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
| `fluids.bucketChanges` | `false` | Opt in to persistent player bucket changes |
| `entities.deaths` | `false` | Opt in to living-entity and player death capture |
| `entities.nameChanges` | `false` | Opt in to tracked custom-name changes |
| `entities.taming` | `false` | Opt in to successful standard animal/horse taming |
| `entities.breeding` | `false` | Opt in to accepted standard animal offspring births |
| `entities.itemFrames` | `false` | Opt in to frame placement, display/rotation and destructive removal history |
| `activity.chat` | `false` | Opt in to public chat capture |
| `activity.sessions` | `true` | Login/logout event capture |
| `activity.commands` | `false` | Opt in to parsed player command capture |
| `activity.commandArguments` | `false` | Include permitted command arguments in new records |
| `activity.commandExcludedRoots` | empty | Additional comma-separated command roots to omit |
| `storage.backend` | `SQLITE` | Database backend; use SQLite with standard jars |
| `storage.sqliteFile` | `guardian.sqlite` | Database filename inside the server's Guardian directory |
| `lookup.defaultResults` / `lookup.maxResults` | `10` / `500` | History page defaults and limits |
| `lookup.maxRadius` | `100` | Maximum lookup radius |
| `rollback.defaultRadius` / `rollback.maxRadius` | `10` / `100` | Block rollback region limits |
| `rollback.maxRecords` / `rollback.blocksPerTick` | `5000` / `100` | Maximum rollback size and work per tick |

Item history has a hard maximum of 500 results per page even if the configured lookup limit is higher. Performance settings control the bounded writer queue, batch size and flush interval. Increasing them does not certify production capacity; monitor `/guardian status` and server logs for queue failures or skipped captures.

## Supported behavior and limits

Guardian captures player block placement/breaking, accepted supported block interactions, ordinary player inventory moves, supported block-backed menu changes, cursor returns, standalone drops, offhand swaps, accepted creative slot changes and exact vanilla 2×2/3×3 crafting paths. Optional hopper and Fabric WorldEdit capture are available as described above.

Registered modded blocks/items can appear in history when they use supported hooks. Arbitrary custom menus, extended crafting, capability-only storage and mod mutations that bypass those hooks are not universally supported. Fluid spread/automation, arbitrary entity-property changes, entity rollback, private-message logging, economy/faction storage and full CoreProtect parity are outside this release.

## Troubleshooting

- **History commands missing after login:** alpha.88 fixes the login command-list check for
  `commands`, `entities` and `fluids`. Install the updated server jar and reconnect. Capture
  switches control new records, not command availability. Command history requires
  `guardian.commands` / operator level 3; entities and fluids use their separate level-2
  permissions. Explicit provider denials still apply. On alpha.87, console deop/op while the
  player stays connected refreshes the list temporarily; restarting the old version repeats
  the login problem. `verboseStartup` does not enable commands.


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
