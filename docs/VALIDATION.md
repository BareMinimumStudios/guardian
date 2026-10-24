# Release validation

Alpha.67 is the current executable checkpoint. Alpha.62 cleanup changed documentation and build-file formatting. Alpha.63 fixes the shared journal shutdown completion ordering found by GitHub CI; it adds no gameplay features. [Release scope](RELEASE_SCOPE.md) is the fixed acceptance contract.

## Completed checks

- Alpha.67 passed 597 automated tests with no failures/errors, clean and repeated configuration-cache builds, and the separate WorldEdit build. The final clean build reused unchanged common/test outputs from the successful full restore build; final NeoForge permission wiring was rebuilt and runtime-tested.
- 2,068 current server-side permission checks passed across actual LuckPerms and provider-free operator fallback on both loaders, including the new activity nodes and block-preview revocation boundaries. The prior 1,150 checks remain historical evidence for the earlier permission paths. Synthetic players do not certify network login or client presentation.
- Both core jars pass archive/version/loader checks, include license material, contain only SQLite JDBC as a nested jar and stay below 16 MiB. The separate server-only Fabric adapter carries GPL license material and does not bundle WorldEdit.

Detailed evidence: [permissions](validation/permissions-alpha62.json), [artifacts](validation/artifacts-alpha62.json), [prior durability baseline](validation/durability-alpha61.json). Artifact evidence includes exact sizes/checksums and previous runtime-tested hashes. Historical unsupported bypasses in durability evidence are limitations, not promises of item apply safety.

## Source cleanup, 2026-10-11

Archived and removed 75 superseded documents and historical reports. The README now provides end-user installation, commands, permissions, backup and troubleshooting instructions. Four current reference guides and three machine-readable evidence reports remain. Active recovery infrastructure, registered mixins and meaningful tests were retained. Only build-file whitespace changed; jar SHA-256 hashes are unchanged.

Clean build (18s), build (8s), repeat with configuration-cache reuse (4s) and WorldEdit build (8s) passed with Java 21 and Gradle 9.8.0. All 522 cached test results passed with no failures/errors.

## GitHub CI correction, 2026-10-11

The [failed run](https://github.com/BareMinimumStudios/guardian/actions/runs/38115735874) failed at the multi-host drain assertion, not compilation or missing dependencies. Independent completion observers allowed the shared drain to finish before the retention observer reported completion. The aggregate now waits for that exact observer.

The alpha.63 full clean build ran without build-cache reuse: 546 tests passed, including 25 schedules of the reported regression. Required repeat/configuration-cache and WorldEdit builds passed. [Evidence and artifact hashes](validation/ci-alpha63.json). Remote CI remains unverified until this local commit is pushed; no push or publish was performed. Alpha.62 provider runtime evidence remains historical evidence for unchanged permission paths.

## Activity logging, 2026-10-17

The owner authorized public chat and login/logout logging. Alpha.64 adds schema 11, immutable activity records, opt-in public chat, separately configurable session capture and separately permissioned paged searches. Commands and private-message types are excluded. Existing item apply exclusions remain.

551 automated tests passed, including SQLite/DuckDB/memory filtering and idempotence, restart persistence, old-schema migration preservation and activity paging. Required clean/repeated-cache/WorldEdit builds passed. Both isolated loader servers resolved the new chat mixin and recorded exactly the expected public chat, login and logout fixture records; private-message types and disabled switches produced no records. SQLite integrity and restart persistence passed. [Evidence and hashes](validation/activity-alpha64.json).

The broadcast test calls the actual Minecraft broadcast method. The synthetic session fixture calls shared capture directly and does not certify a network login/logout. Activity permissions under installed providers were pending at this checkpoint and subsequently passed in the alpha.66 acceptance section below. Connected-client chat display and actual pack cancellation behavior remain pending.

## Extended history search, 2026-10-17

The owner authorized multi-user searches, user exclusions, exact resource include/exclude lists and matching completion. Alpha.65 applies these filters before paging in SQLite, optional DuckDB and memory. Schema 12 backfills a derived item-ID index while preserving source payload bytes. Exclusions hide whole events/transactions; block records match before or after identity. Rollback and item preview explicitly refuse extended filters.

561 automated tests passed, including aliases/malformed input, comma/negation completion, modded IDs, UUID resolution, unknown actors, exclusion precedence, pagination, restart and historical migration over the 128-record batch boundary. Clean, repeated configuration-cache and optional WorldEdit builds passed. Two isolated servers passed nine command cases each against a synthetic persisted audit prefix, including history paging, exclusions and rollback refusal, then restart. No rollback claims were created; SQLite integrity passed. Runtime core hashes match final artifacts. [Evidence and checksums](validation/filters-alpha65.json).

These server tests do not certify actual client completion presentation, all modpack integrations or capture of arbitrary modded events. Activity permission/provider checks subsequently passed in the alpha.66 acceptance section below; connected-client acceptance remains pending.

## Block rollback preview, 2026-10-18

Alpha.66 adds `/guardian rollback preview` and `/guardian rb preview` using the existing single-user rollback limits and permissions. Preview simulates each position's history, reports conflicts, unloaded chunks and unsupported targets, and shows up to five examples. It writes neither blocks nor journal claims and does not load chunks. Apply remains a separate command with fresh checks; preview is not a reservation or guarantee.

589 automated tests passed, including four preview-plan tests and 25 repetitions of the shutdown regression. The clean, repeated configuration-cache and optional WorldEdit builds passed. A reproduced shutdown race was fixed by requiring drained operation hosts to finish their terminal classification before backend closure.

Both isolated loaders passed nine cases each with a synthetic audit prefix through the actual writer. World readback confirmed preview left stone unchanged and a separate apply restored air. All five preview journal flags stayed unchanged; after apply, exactly one record was rolled back. Unloaded targets, unsupported targets, blocked chains, extended-filter refusal and restart were checked. SQLite integrity passed, and tested core hashes match final artifacts. [Evidence and checksums](validation/block-preview-alpha66.json). The pushed alpha.65 GitHub build also passed. Permission-provider acceptance subsequently passed in the section below. Real connected-client acceptance remains pending; these fixtures do not certify arbitrary modpack behavior.

## Activity and preview permission acceptance, 2026-10-18

The unchanged alpha.66 jars passed 2,068 checks on four isolated servers: LuckPerms Fabric/NeoForge and provider-free operator fallback on each loader. Chat and session grants are independent of each other and ordinary lookup. Unset permission, explicit denial for an actual operator, grant removal and cached time/user suggestions were checked. Sixteen individual activity query cases per loader covered both aliases, success and controlled failure, with and without permission revocation before deferred output.

Block preview was cancelled during planning, before its first tick and between ticks after 100 of 101 rows. Revoked success/failure callbacks emitted no results; authorized completion/failure still emitted results. All 101 actual stone blocks and all 101 ACTIVE journal rows remained unchanged on each provider server. Operator entries at levels zero through four confirmed level-three access for chat, sessions and block preview, then access removal. Real permission decisions were not mocked. [Evidence and artifact hashes](validation/acceptance-alpha66.json).

The pushed executable [GitHub run](https://github.com/BareMinimumStudios/guardian/actions/runs/39099179448) passed. No executable files or jar hashes changed in this acceptance pass. R2 is complete for server-side activity/preview permissions; actual network login, client presentation and pack acceptance remain R3. Private agents and test servers are excluded from the source repository.

## Block restore/redo, 2026-10-23

The owner explicitly selected block restore/redo. Alpha.67 adds `/guardian restore`, `/guardian redo` and their read-only preview branches under both root aliases. The separate `guardian.restore` permission defaults to operator level three and is registered with NeoForge's actual PermissionAPI. Restore selects ROLLED_BACK/RESTORE_PENDING rows oldest first, inverts source/target only for replay, and returns successful original rows to ACTIVE. Schema 13 protects the new pending-state interpretation from older builds. History and payloads are preserved.

597 automated tests passed, including eight new tests for forward simulation, blocked chains, unsupported/unavailable targets, matching-target reconciliation, state predicates before paging across memory/SQLite/DuckDB, repeated state cycles and schema-12 upgrade/restart. Historical migration fixtures were corrected to remove all newer migration markers when reconstructing old schemas. Clean, repeated-cache and WorldEdit builds passed. No mixins changed.

Final core jars passed 28 command/world/database checks per loader, including rollback/restore/rollback, both preview aliases, no preview mutation, repeated restore with no work, conflicts, unsupported targets, unloaded chunks, pending target reconciliation, unresolved pending preservation and restart replay without duplicate audit records. Actual LuckPerms and provider-free operator fixtures passed 2,420 checks, including preview revocation boundaries and seven apply stages: planning, before claim, during claim, before apply, preservation of older pending work, authorized apply, and permission loss after mutation before final journal completion. Tested hashes equal final artifacts. [Evidence and checksums](validation/restore-alpha67.json).

Pending-state fixtures are synthetic; this does not certify atomic persistence between SQLite and Minecraft chunk saves or a new process-kill recovery guarantee. Connected-client/claim/pack acceptance, including the new restore command, remains R3. No item apply or purge was added. The owner pushed alpha.67; its [GitHub run](https://github.com/BareMinimumStudios/guardian/actions/runs/40058349580) passed.

## Alpha.67 artifact review, 2026-10-23

The owner pushed executable commit `e0f6a99`. Its [GitHub run](https://github.com/BareMinimumStudios/guardian/actions/runs/40058349580) passed. All three local jars still match the hashes from the completed restore/runtime milestone. Metadata confirms version, server-only loader/dependency declarations, SQLite-only standard packaging, separate adapter licensing and a 256×256 core icon. Core sizes are 12.36 MiB (Fabric) and 12.33 MiB (NeoForge); the separate Fabric WorldEdit adapter is about 0.02 MiB. ZIP integrity and nested-jar metadata passed. No executable or dependency changes were made, so the existing 597-test, 56-case restore and 2,420-check permission baseline was retained rather than rerun. [Artifact evidence](validation/artifacts-alpha67.json).

A private test ZIP contains separate loader cores and the optional Fabric adapter, license notices, SHA-256 checksums, installation/upgrade instructions and a blank six-row client acceptance sheet. It contains no server worlds, databases, private agent sources or machine-specific configuration. Installation instructions call out schema 13, separate restore permission and original-event time filters. This is an acceptance package, not a published release. R3 client results and final owner sign-off remain pending.

## Pending acceptance

Connected-client acceptance remains pending on Fabric and NeoForge. Run the six rows in release scope when a tester is available, including actual pack/claim behavior. Final release sign-off follows those results. No publish or push has been performed. Idle startup, synthetic inventories and packet queues do not close client acceptance or certify production load capacity.

## Artifact use

The private acceptance package separates Fabric and NeoForge core jars and the optional Fabric WorldEdit adapter. It includes installation notes, a blank client test sheet, licenses and SHA-256 checksums. Install only the matching loader core. Fzzy Config, loader Kotlin support, Fabric API where required and optional WorldEdit remain external server dependencies.
