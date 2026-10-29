# Guardian command guide

[Back to the README](../README.md)

Replace `Alex` with a real player name. `/co` is an alias for `/guardian`.
The short forms `l`, `i` and `rb` mean lookup, inspect and block rollback.

## Searches

| What you want | Example |
| --- | --- |
| Block changes | `/guardian lookup u:Alex t:1h r:10` |
| Item movements | `/guardian transactions u:Alex t:1h r:10` |
| Item history menu | `/guardian transactions ui u:Alex t:1h` |
| Public chat | `/guardian chat u:Alex t:1d` |
| Login/logout events | `/guardian sessions u:Alex t:7d` |
| Player commands | `/guardian commands u:Alex t:1h` |
| Sent and received whispers | `/guardian whispers u:Alex t:1h` |
| Entity deaths | `/guardian entities a:death t:1h r:25` |
| Taming or breeding | `/guardian entities a:tame u:Alex t:1d`; use `a:breed` for births |
| Item frames | `/guardian entities a:frame t:1h r:5` |
| Bucket actions | `/guardian fluids u:Alex t:1h r:25` |
| Natural fluid flow | `/guardian fluids a:flow t:10m r:20` |
| Sign edits and appearance | `/guardian lookup c:sign t:1h r:10` |
| Lectern book/page interactions | `/guardian lookup c:lectern t:1h r:10` |
| Fire or explosions | `/guardian lookup c:fire,explosion t:1h r:25` |
| Growth or leaf decay | `/guardian lookup c:growth,decay t:1h r:25` |
| Pistons or falling blocks | `/guardian lookup c:piston,gravity t:1h r:25` |

Enable the matching logging category **before** making the change.
See [logging settings](../README.md#choose-what-to-log).

Green item entries mean items were added; red means removed. A hopper route shows
the source and destination of that particular transfer. An item reaching a hopper
does not mean it also reached the barrel below it.

Player command logging omits arguments by default. `activity.commandArguments` is a
separate opt-in, with sensitive command roots excluded. Public chat logging does not
include private messages. Set staff permissions and tell players what your server logs.

## Whispers and live spy

Enable `activity.whispers` in the server config and restart before sending a test message.
Vanilla `/msg`, `/tell` and `/w` are supported. Other mods' private channels and console-sent
messages are not covered. History records native dispatch, not proof that a player read it.

```text
/guardian whispers u:Alex t:1h
/guardian whispers u:Alex p:2
```

User filters match either the sender or recipient. Excluding a user excludes either side.
One row is recorded per recipient. Private messages stay out of public chat and ordinary
command history, even when command arguments are enabled. Filtered text is respected;
fully filtered messages and recipients with hidden chat are skipped.

To watch new activity, enable `activity.liveSpy` **and** the matching capture switch:

```text
/guardian spy whispers on
/guardian spy commands on
/guardian spy whispers off
/guardian spy commands off
```

Each channel needs `guardian.spy` plus `guardian.whispers` or `guardian.commands`.
Subscriptions are personal and last until logout/restart or an observed permission removal.
Permissions are checked again before every delivery. Spy shows at most 20 updates per viewer
per server tick, then one suppression notice; accepted history is still saved. At most 256
staff subscriptions are active at once. Use history searches to catch up.

Command spy follows `activity.commandArguments` and `activity.commandExcludedRoots`.
Arguments are off by default. Authentication/password commands stay excluded; add any custom
sensitive command aliases to the exclusions before enabling arguments. Spy is a view of
accepted records, not a way to bypass disabled logging or exclusions.

`/guardian export whispers u:Alex t:1h` writes bounded private-message history and requires
`guardian.export` plus `guardian.whispers`. Global old-history purge includes whispers;
world/type-scoped purge retains them. Tell players what your server records, restrict these
permissions, and protect the database and exported files. Logging is not retroactive.

## Search filters

| Filter | Example | Meaning |
| --- | --- | --- |
| `u:` / `user:` | `u:Alex,Bob` | Either player |
| `u:` with `!` | `u:!Alex` | Everyone except Alex |
| `t:` / `time:` | `t:1d12h` | Last day and twelve hours |
| `r:` / `radius:` | `r:10` | Ten blocks in each direction around you |
| `x: y: z:` | `x:100 y:64 z:-20` | Search around these coordinates |
| `i:` / `include:` | `i:stone,example:slate` | Include these IDs |
| `e:` / `exclude:` | `e:diamond` | Hide events containing these IDs |
| `a:` / `action:` | `a:place,break` | Category-specific action |
| `c:` / `cause:` | `c:fire` | Cause in block history |
| `l:` / `limit:` | `l:20` | Results per page |
| `p:` / `page:` | `p:2` | Page number |
| `o:` / `order:` | `o:oldest` | Oldest results first |

Time units: `s` seconds, `m` minutes, `h` hours, `d` days, `w` weeks.
Supply all three coordinates together. Exclusions win over inclusions.
Use exact registry IDs; an omitted namespace means `minecraft:`. Display names, tags and
wildcards are not accepted. Item filters select whole transactions, including their other items.

Block, entity and fluid searches use your current dimension. Item searches without
a location can cover all dimensions. Chat, sessions, commands and whispers are server-wide and
accept user, time, page, limit and order filters; they have no position or item filter.

With the optional Fabric WorldEdit adapter, `r:#we` uses your cuboid selection.
For a specific recorded movement, click its history row or use `m:<movement UUID>`.

## Inspector

Run `/guardian inspect` to enable it and again to disable it.
Left-click blocks for block history, right-click supported containers for item history.
Click chest/hopper minecarts for their UUID-linked item history. Their recorded coordinates
may differ from their current position.

```text
/guardian inspect page 2
/guardian inspect order oldest
/guardian inspect ui
```

The UI shows recorded changed slots, not a complete past inventory. Hover for IDs,
counts and ownership. Icons are safe previews; custom recorded item names and effects
are not loaded into the menu. You cannot take or edit the displayed items.

## Block rollback and restore

First check the history, then preview and apply:

```text
/guardian lookup u:Alex t:10m r:5
/guardian rollback preview u:Alex t:10m r:5
/guardian rollback u:Alex t:10m r:5
```

Preview changes nothing. Apply checks the world again and skips unavailable or conflicting
blocks. Stay in the same place/dimension and use the same filters.

`/guardian restore preview u:Alex t:10m r:5` previews reapplying rolled-back blocks.
`/guardian restore u:Alex t:10m r:5` applies that redo.

You can narrow block rollback/restore with multiple users and block IDs.
Environmental, movement, sign and lectern records are excluded from recovery.
Block rollback does not restore entities or items.

## Item rollback

Use the [step-by-step item rollback guide](ONLINE_ITEM_ROLLBACK.md).
It has its own preview, apply selection and confirmation. It supports one included player
and a bounded region; multi-user and item-type filters are not accepted.

If it refuses, read the specific Player/Container diagnostic. A conflicting inventory
must not be overwritten. For an interrupted operation, follow [recovery](ITEM_RECOVERY.md);
keep the database and world/player saves together.

## Export results

```text
/guardian export blocks u:Alex t:1h r:10 l:100
/guardian export items u:Alex t:1d l:100
```

Other categories are `chat`, `sessions`, `commands`, `entities` and `fluids`.
Each export needs `t:`. Up to 500 records can be requested, subject to configured limits.
The server saves a JSON Lines file under `guardian/exports`; retrieve it through your
server files. This is a history extract, not a backup or an importable rollback file.
Exports can contain private text and saved item data. Keep them private.
Use `/guardian export cancel` to cancel unfinished work.

## Delete old history

Back up the database first.

```text
/guardian purge preview t:30d
/guardian purge confirm <token shown by preview>
```

This deletes history **older than** thirty days, across all users and worlds.
It does not change the world or inventories. Read the preview's scope before confirming.
Each confirmation deletes at most 1,000 records. Repeat preview/confirm for another batch.
The minimum retained age is one day.

To narrow the scope, add `w:minecraft:overworld` for one dimension, or `i:stone,coal`
and `e:example:protected_item` for block/item types. A world filter retains chat,
session, command and whisper records; adding type filters limits deletion to block/item history.
A matching item transaction is deleted as a whole.

Recovery-protected history is retained. Old item rollback checks can refuse history
after a purge because its transfer chain may be incomplete. Purge cannot be undone
without a backup. `/guardian purge cancel` discards a pending request; it cannot undo
a batch already committed. The database file may not shrink immediately.

## Permissions

Operators can use commands at the fallback level below. If a permissions provider
explicitly denies a node, operator status does not override that denial.

| Node | Access | Vanilla operator fallback level |
| --- | --- | --- |
| `guardian.lookup` | Block and item history | 2 |
| `guardian.inspect` | Inspector clicks and pages | 2 |
| `guardian.chat` | Public chat history | 3 |
| `guardian.sessions` | Login/logout history | 3 |
| `guardian.commands` | Parsed player command history | 3 |
| `guardian.whispers` | Private-message history | 3 |
| `guardian.spy` | Live spy; also needs the channel history grant | 3 |
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

For LuckPerms, grant individual nodes, for example:

```text
/lp user Alex permission set guardian.inspect true
/lp user Alex permission set guardian.lookup true
```

Item apply needs both `guardian.rollback` and `guardian.rollback.items`.
Export needs `guardian.export` plus the selected history category's permission.
Inspecting item frames also needs `guardian.entities`.
