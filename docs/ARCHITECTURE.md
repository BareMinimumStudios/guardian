# Guardian architecture

`common` contains immutable audit values, JDBC storage, bounded queues, filters and pure rollback/recovery decisions without Minecraft or loader imports. `minecraft` provides shared Mojang-mapped snapshots, restoration, menus, hooks, configuration, commands and runtime services. Fabric and NeoForge modules install their lifecycle, events and permission integration. Each loader runtime packages the common output directly.

Capture snapshots state on the server thread and submits immutable entries to the bounded writer pipeline. JDBC writes are batched off-thread. Rejected capture and persistence failures remain visible; queue acceptance alone does not prove durable history. Read-only inventory checks coordinate with a flushed audit prefix and owner/history checks. Persistent journals, unresolved protection and saved images retain recovery evidence across failures.

Block rollback reconciles recorded before/after states, claims bounded batches and finalizes journal state after verified restoration. Unloaded chunks are skipped; current state conflicts are refused. Permission revocation prevents further mutations while started journal operations settle safely.

Item preview and recovery observe supported owners without exposing an item apply command. Exact supported container bindings and player inventory checks refuse unsupported/busy/loot-backed endpoints. Existing internal coordination does not establish exclusive access against arbitrary raw stack/component or world mutations. The disabled apply boundary is deliberate; see [release scope](RELEASE_SCOPE.md).

The optional Fabric WorldEdit adapter translates extent mutations and selections through Guardian's own integration API. It is separately GPL licensed. Core artifacts have no WorldEdit dependency. Standard core jars bundle SQLite only; the optional DuckDB variant is a build choice, not an automatic download.
