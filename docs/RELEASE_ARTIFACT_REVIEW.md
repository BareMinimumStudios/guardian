# Alpha.62 artifact review

Reviewed 2026-10-10. This review changes documentation and packaging only. Fabric core and adapter now include their existing license files; executable source is unchanged from the completed alpha.62 validation.

- Fabric and NeoForge core jars pass archive integrity, version and loader metadata checks. Each contains only SQLite JDBC as a nested jar and stays below 16 MiB. Both contain a 256×256 icon and license material.
- Fabric metadata declares a server environment; NeoForge declares server dependency sides and ignores the server mod version in client compatibility checks. Connected-client behavior still needs R3 acceptance.
- Fzzy Config remains an external server dependency. Fabric also requires Fabric API and Fabric Language Kotlin; NeoForge requires Kotlin for Forge. Clients do not need Guardian or Fzzy Config.
- The separate Fabric WorldEdit adapter passes archive/version checks, declares server use and requires WorldEdit. Its GPL license remains separate from the BML core; WorldEdit is not bundled.
- The version has a dated Keep a Changelog entry. The publishing workflow validates matching artifacts and uses mc-publish. No upload, push or release was performed.

Exact sizes and SHA-256 hashes are in [artifact evidence](validation/artifacts-alpha62.json). The existing [permissions evidence](validation/permissions-alpha62.json) records runtime tests. Clean, repeated configuration-cache and WorldEdit builds were rerun after the license packaging correction; all 522 tests passed.

The private test package contains both loader jars in separate folders, the optional adapter with its own license, installation instructions, checksums and the six-row client acceptance sheet. Install only the core jar for your loader. Back up the world and database before testing schema 10; do not downgrade the migrated database.

R4 artifact checks are complete. Final release sign-off remains pending alongside R3 connected-client acceptance. Item apply remains unavailable. These checks do not certify arbitrary modpacks, network login or production load capacity.
