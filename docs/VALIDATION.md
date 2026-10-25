# Release validation

Current executable checkpoint: **0.4.0-alpha.91+1.21.1**. Schema 24, config version 17.
The owner authorized targeted acceptance fixes for signs, displays, pickups and UI navigation.

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
Alpha.90 CI awaits the owner's push.

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
