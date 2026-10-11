# Release validation

Alpha.62 is the current executable checkpoint. The release cleanup changes documentation and build-file formatting only; it does not add features or alter gameplay. [Release scope](RELEASE_SCOPE.md) is the fixed acceptance contract.

## Completed checks

- 522 automated tests pass with no failures or errors. Required clean build, repeated build with configuration-cache reuse and separate WorldEdit build passed. The license packaging correction reran those builds; test outputs were restored from Gradle cache.
- 1,150 server-side permission checks passed across actual LuckPerms and provider-free operator fallback on both loaders, including revocation, deferred output, cached suggestions and persistent block rollback boundaries. Synthetic players do not certify network login or client presentation.
- Both core jars pass archive/version/loader checks, include license material, contain only SQLite JDBC as a nested jar and stay below 16 MiB. The separate server-only Fabric adapter carries GPL license material and does not bundle WorldEdit.

Detailed evidence: [permissions](validation/permissions-alpha62.json), [artifacts](validation/artifacts-alpha62.json), [prior durability baseline](validation/durability-alpha61.json). Artifact evidence includes exact sizes/checksums and previous runtime-tested hashes. Historical unsupported bypasses in durability evidence are limitations, not promises of item apply safety.

## Source cleanup, 2026-10-11

Archived and removed 75 superseded documents and historical reports. The README now provides end-user installation, commands, permissions, backup and troubleshooting instructions. Four current reference guides and three machine-readable evidence reports remain. Active recovery infrastructure, registered mixins and meaningful tests were retained. Only build-file whitespace changed; jar SHA-256 hashes are unchanged.

Clean build (18s), build (8s), repeat with configuration-cache reuse (4s) and WorldEdit build (8s) passed with Java 21 and Gradle 9.8.0. All 522 cached test results passed with no failures/errors.

## Pending acceptance

Connected-client acceptance remains pending on Fabric and NeoForge. Run the six rows in release scope when a tester is available, including actual pack/claim behavior. Final release sign-off follows those results. No publish or push has been performed. Idle startup, synthetic inventories and packet queues do not close client acceptance or certify production load capacity.

## Artifact use

The private acceptance package separates Fabric and NeoForge core jars and the optional Fabric WorldEdit adapter. It includes installation notes, a blank client test sheet, licenses and SHA-256 checksums. Install only the matching loader core. Fzzy Config, loader Kotlin support, Fabric API where required and optional WorldEdit remain external server dependencies.
