# Release validation

Current executable checkpoint: **0.4.0-alpha.101+1.21.1**. Schema 30, config version 19.
The owner authorized targeted acceptance fixes for signs, displays, pickups and UI navigation.

## Alpha.101 inventory checkpoints

All 22 selected version/loader pairs pass the isolated native capture, full restore,
saved-file verification and before-state undo test (46 assertions each). Both loaders
on 1.21.1 and 26.2 also pass admission conflict (26), incompatible version (16) and
opt-in periodic capture/deduplication (20) checks. Viewer actions use virtual slots;
they never execute historical item components.

Four endpoint pairs pass the selected nine-stage sequence including normal restore,
partial saved write and abrupt exit, protected startup, maintenance preview,
incompatible-version repair refusal, wrong token, targeted apply, retry and restart
readback. Refused/preview operations preserve inventory-file hashes. These are
synthetic players and private databases, not connected-client acceptance.

The baseline and modern shared suites pass 740 and 742 tests.
They run once for their respective inventory-image implementations. Each remaining
target receives clean Fabric/NeoForge builds and its own Minecraft unit tests;
identical shared database tests are not needlessly rerun per target. Existing
creative recovery and transfer redo are rerun on both endpoint loaders. Artifact
metadata, exact-version resources, SQLite-only bundling, configuration-cache reuse
and the separate baseline Fabric WorldEdit adapter are checked.

Schema 29 adds standalone checkpoint authority and preserves schema-28 protected
redo receipts. Schema 30 adds only budget/name query indexes; upgrades preserve
protected schema-29 checkpoint receipts too. The interruption sequence ran before
this index-only migration. Final target jars and normal/empty inventory runs include
the migration. Configuration 20 defaults automatic inventory capture to off.

A separate production-JDBC fixture with 5,000 complete images (8 KiB opaque data
each) measured median budget queries at 2.670 ms before indexing and 0.110 ms after,
and offline-name queries at 13.114 ms before and 0.038 ms after. There were 10 warmups
and 101 samples. Covering query plans and unchanged results are checked. A tested
time index gave little benefit and is not shipped. These are query-only synthetic
measurements, not end-to-end command latency, native component decoding or server
capacity guarantees.
[Evidence](validation/inventory-checkpoints-alpha101.json) and [usage](INVENTORY_BACKUPS.md).

## Alpha.100 item redo

All 22 selected version/loader pairs pass the native online redo check. Each test
reverses a seeded transfer through the production rollback service, then previews,
selects and confirms redo through the registered public commands. Names, damage,
complete saved inventories, parent links and repeat-redo refusal are checked.

Both loaders on 1.21.1 and 26.2 pass an additional unrelated-slot conflict check
and the selected eight-stage interruption/recovery sequence. A partial player file
is saved before abrupt exit; startup protection, read-only maintenance preview,
wrong-token refusal, confirmed repair, retry and restart readback are verified.
These are synthetic players and isolated worlds, not connected-client acceptance.

The clean baseline build passes 837 tests. Most newer targets pass 960;
1.21.4 passes 962 and 1.21.5 passes 964. Nine journal tests exercise SQLite and optional DuckDB,
including full-image integrity, duplicate claims, cancellation, restart and ordering.
All target builds, repeated baseline configuration-cache reuse, the separate Fabric
WorldEdit adapter, 13 publisher checks and 22 runtime artifact audits pass. Normal
creative recovery is also rerun on both loaders for 1.21.1, 1.21.4, 1.21.5 and 26.2.

The sweep caught an inherited 43-slot preview check on the 41-slot 1.21.4/1.21.5
inventories. The adapter now uses the correct layout, with an automated regression
against Minecraft's actual Inventory plus both-loader native eligibility checks.

Schema 28 retains existing transfer/creative purposes and fingerprints, adding a
linked redo claim. Configuration version 19 is unchanged. This milestone does not
add custom-inventory recovery or complete historical inventory checkpoints.
[Evidence](validation/item-redo-alpha100.json) records the exact scope.

## Alpha.99 follow-up review

The shared planner avoids a second component-payload conservation scan for each transfer;
its independent action, owner, state and conservation refusals remain. All target builds
pass (828 baseline tests; 951 per newer target, or 953 on 1.21.5). Native saved transfer
rollback and creative recovery were rerun on both loaders for 1.21.1 and 26.2: eight
checks pass, including component preservation and duplicate-source refusal.

The publisher passes 13 checks, binds release-note hashes, avoids unused version-history
requests during access checks and streams jar hashes. A five-run local benchmark on the
baseline jar measured peak Python allocation dropping from 13,602,645 to 271,601 bytes,
with identical hashes. This is upload-tool memory, not a server performance measurement.
[Review evidence](validation/review-alpha99.json) separates this pass from the earlier
interruption matrix. Real-client and production-modpack acceptance remain pending.

## Alpha.99 creative item recovery

All 22 selected Minecraft/loader pairs passed eight native stages: creative deletion
capture and confirmed recovery, interrupted partial save, protected startup, maintenance
preview, wrong-token refusal, repair, idempotent retry and restart readback. Tests use
actual server-player packet handling with synthetic identities and no connected client.
Names, damage, full saved-slot images, permanent source claims and SQLite integrity are
checked. Preview, refusal and retry preserve native inventory file hashes.

The baseline clean build passes 828 automated tests. Each newer target passes 951 tests,
except 1.21.5 with 953, including additional equipment tests. The repeated baseline build reuses
its configuration cache, and the separate Fabric 1.21.1 WorldEdit adapter build passes.

Schema 27 persists separate creative-recovery authority. Existing schema-26 transfer
journals and protected images migrate without gaining recreation authority; normal
transfer rollback still requires item conservation. SQLite and the optional DuckDB
backend are covered by migration tests. Config version 19 is unchanged.

[Evidence](validation/creative-recovery-alpha99.json) records the version/loader matrix
and exact limits. This does not certify real-client UI, permission providers, modpacks
or every possible crash boundary. Full historical inventory backups were not included in alpha.99.

## Alpha.98 rollback review

Native block rollback/redo and restart checks passed on both baseline loaders. The review
fixed unchanged-state block-entity restoration and the baseline development launch classpath.
Newer-version item rollback, process interruption and targeted recovery checks are recorded
separately in [port validation](PORT_VALIDATION.md). Schema 26/config 19 are unchanged.

## Transaction and item rollback optimization

820 automated tests pass. The clean build, repeated build with actual configuration-cache reuse
and separate WorldEdit adapter build pass. Schema 26/config 19 are unchanged.

The shipped JDBC benchmark uses 200,000 added synthetic transactions with valid payloads and
full event-time location contexts in an isolated database. On Fabric, sparse-radius median
lookup improved from 183.95 ms in alpha.95 to 18.93 ms in alpha.96. A dense query measured
1.06 ms versus 1.31 ms, including the new probe overhead; a global page measured 1.08 ms versus
0.70 ms. NeoForge alpha.96 also passed the same decoded-result checks. All compared IDs and
ordering matched. These are warm isolated lookup/decode measurements, not production latency
or TPS guarantees. The lower-level SQL fixture and its dense-plan tradeoff are recorded separately.

Native Fabric and NeoForge checks verify actual preview diagnostics, saved apply/confirmation,
invalid-token and duplicate-claim refusal, and all timing stages in status. Existing guard,
owner, audit-fence and durable-save requirements remain. Timing totals can include refusals
or the first recovery-required report and are not success receipts.
[Evidence](validation/transaction-optimization-alpha96.json) records measurements, artifacts and limits.
Synthetic players do not replace real-client/production-pack acceptance.

## Activity readability and spy polish

Alpha.95 adds personal display filters, safe shared formatting, bounded literal text searches,
status/off controls and content-free query timings. [Evidence](validation/spy-polish-alpha95.json)
records tests, native fixture results and formatting measurements. Schema 26/config 19 are
unchanged. Client hover/layout and production-pack acceptance remain open.

## Whisper history and live spy

808 automated tests pass, including participant filters, schema-25 upgrade/restart,
private export and purge. Isolated native Fabric and NeoForge fixtures exercise vanilla
`/msg`, `/tell` and `/w`, disabled capture, filtering, permissions and bounded live spy.
[Evidence](validation/whispers-spy-alpha94.json) identifies the final builds and jars.
These use synthetic players and in-memory network channels; real-client acceptance remains open.

## Bug review: sealed loot and lookup boundaries

801 automated tests pass. The sealed-loot spectator issue was reproduced with unchanged
alpha.92 jars on isolated Fabric and NeoForge servers. Patched native tests retain the
unopened loot table and refuse the spectator menu, while ordinary minecart, hopper and
lectern capture still produces the expected saved rows.
[Review evidence](validation/bug-review-alpha93.json) records the exact builds and runtime
checks. Real-client and production-pack acceptance remain pending.

## Minecart inventories and lecterns

795 automated tests and the required clean/cache-reuse/WorldEdit builds pass. Native Fabric
and NeoForge fixtures verify actual cart menu click packets, hopper/cart transfers, rejected
full destinations, ground collection and lectern insertion/open/page/removal paths.
The saved databases contain the expected event counts and pass integrity checks.
Schema 24 upgrade/restart, UUID/spatial queries, purge and recovery exclusions have regression tests.
The fixtures use synthetic players; NeoForge lectern synchronization uses an in-memory network
channel. They do not certify real-client menus or the production pack.
[Evidence](validation/minecarts-lecterns-alpha92.json).

## Plaque and copper capture

786 automated tests, required builds and native Fabric/NeoForge tests pass. Supplementaries
Squared 1.2.18 plaques use the optional text-holder capture path; supported copper wax/oxidation
surface transitions use player block history. Accepted changes are distinct from no-op/refused
attempts. Custom plaque GUI reception and real-pack acceptance still need client testing.
[Evidence](validation/plaques-copper-alpha91.json).
Alpha.90 CI passed: [run](https://github.com/BareMinimumStudios/guardian/actions/runs/40225463098).

## Item preview diagnostics

781 automated tests and required builds pass. Both-loader native checks exercise distinct
inventory rejection reasons, final-binding invalidation and the actual public preview detail.
The same fixtures then complete native shift-click apply/confirm with verified saved counts
and components. No supplied server data was changed. The reported pack-specific rejection
still needs its new detail line; no safety check was disabled.
[Evidence](validation/item-preview-diagnostics-alpha90.json).
Alpha.89 GitHub CI passed: [run](https://github.com/BareMinimumStudios/guardian/actions/runs/40206943947).
Alpha.90 CI passed; alpha.91/alpha.92 await the owner's push.

## Sign and display acceptance corrections

781 automated tests and the required clean/cache-reuse/WorldEdit builds pass. Native fixtures
pass on both loaders with the exact reported mod versions and without optional mods. These
cover sign text/colour/glow/wax, display insertion/removal, actual/partial pickups and continuous
transaction navigation. A native inventory-click packet produces the nine-named-coal transfer;
public rollback apply/confirm saves it on both loaders. Real client/pack acceptance remains
open. No supplied server was changed. [Evidence](validation/display-signs-alpha89.json).

## Login command-list correction

During real Fabric client acceptance, the owner reported missing command/entity/fluid history
nodes despite alpha.87, enabled capture and operator access. Console deop/op while connected
restored them. Both vanilla and NeoForge `PlayerList.placeNewPlayer` send the initial command
packet through `sendPlayerPermissionLevel` before `playersByUUID.put`. The command-node
predicates incorrectly treated that visibility check as a history request from a connected player.

Alpha.88 advertises nodes by current permission and keeps identity/permission validation on
execution and deferred output. The native packet regression covers the pre-insertion state,
operator thresholds and both aliases; baseline alpha.87 reproduces the missing nodes. It also
checks pre-login query refusal, joined queries, disconnected-result suppression and revocation.
See [login evidence](validation/login-commands-alpha88.json). No real-client acceptance is claimed
for the replacement jar until the owner reconnects and confirms it. Feature scope is unchanged.

## Optimization baseline evidence

See [alpha.87 measurements, regressions and artifact hashes](validation/polish-alpha87.json).
The required clean build, repeated build with configuration-cache reuse and separate WorldEdit
build pass locally through IntelliJ's Java 21 / Gradle 9.8.0 configurations. 778 automated
tests pass. Alpha.87 passed [GitHub CI](https://github.com/BareMinimumStudios/guardian/actions/runs/40190105399);
alpha.88 remote CI awaits the owner's push.

### UI comparison

Private Fabric/NeoForge servers compare alpha.86 with alpha.87 using the same 36 populated
inventory slots, warm-up and seven rounds of 1,000 native menu updates. Thread allocation
measurements cover those calls, including vanilla synchronization and fixture invocation;
they are not whole-server heap/TPS measurements. Idle bottom-slot replacement falls from
36 to zero. A single count change updates one display copy, and same-count component changes
still refresh. Display copies never alias mutable live stacks. Ignored clicks preserve the
history cards while authoritative ghost correction remains enabled.

Both loaders repeat native packet tests for all seven click modes, forged client predictions,
bottom/outside slots, transaction/result/changed-slot navigation, actual operator revocation,
closed/deferred result suppression and inspector withdrawal. Real player counts/components,
the three seeded audit rows and zero rollback journals remain unchanged. These use synthetic
players with unconnected listeners; they do not certify client presentation.

### History queries

An isolated SQLite database contains 100,000 synthetic transfers across 100 actors and
1,000 container locations. Benchmarks call Guardian's actual JDBC lookup implementation,
with warm-up and 25 samples per query shape. Exact-container lookups previously scanned the
time index and performed a correlated location lookup for each candidate. The equivalent
membership query now starts from the existing location index. Only exact positions, including
radius zero, use that shape; broad world/radius/box queries are unchanged.

Cross-backend tests compare exact and broad results to immutable query semantics across
ordering, pagination, tied times, user/item filters, multiple container contexts and restart.
No cache, new index, schema migration or weaker SQLite save setting is introduced. Results
and query plans are recorded in the current evidence. Timings describe this fixture, not
production throughput or arbitrary pack behavior.

## Prior checkpoints

Historical JSON reports retain their original version-specific results and limitations.
Their old pending statuses do not override newer CI results or the current release contract.

| Checkpoint | Coverage | Evidence |
| --- | --- | --- |
| Alpha.86 | Read-only item UI, native packets, paging and operator withdrawal | [Report](validation/item-ui-alpha86.json), [passed CI](https://github.com/BareMinimumStudios/guardian/actions/runs/40187646202) |
| Alpha.85 | Targeted partial item recovery, process interruption/retry and restart | [Report](validation/item-recovery-alpha85.json), [passed CI](https://github.com/BareMinimumStudios/guardian/actions/runs/40185828976) |
| Alpha.84 | Online item ownership, actual saves, commands and conservative recovery | [Report](validation/online-items-alpha84.json), [passed CI](https://github.com/BareMinimumStudios/guardian/actions/runs/40180697380) |
| Alpha.82 | Filtered export and actual permission-provider baseline | [Report](validation/exports-alpha82.json) |
| Alpha.81 | Sign capture and current permission checks | [Report](validation/signs-alpha81.json) |
| Alpha.78–80 | Movement, scoped purge and block recovery filters | [Movement](validation/movement-alpha78.json), [purge](validation/scoped-purge-alpha79.json), [filters](validation/recovery-filters-alpha80.json) |
| Alpha.67–77 | Block restore, administration and optional capture milestones | Earlier JSON reports in `docs/validation/` |

## Remaining acceptance

The six connected-client rows in [release scope](RELEASE_SCOPE.md) remain pending on both
loaders. Verify actual UI rendering, claims, network-issued commands, item counts/components,
rollback/restart and administration against the intended pack when a tester is available.
Synthetic players, idle startup and isolated query benchmarks do not close that gate.
Final release sign-off follows those results and review of the selected artifacts.

Tests never open the supplied production databases or worlds. All owned test servers stop
after their fixtures. Source/test archives include checksums; core jars remain server-only,
Java 21, BML licensed and SQLite-only, with the optional Fabric WorldEdit adapter separately
GPL licensed. No push, tag or publication is performed by local validation.
