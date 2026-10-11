# Guardian storage schema

Current schema: **10**. `GuardianSchema` and `SchemaMigrator` in `common` are authoritative for migrations. Startup upgrades supported older databases; newer unsupported schemas are refused. Back up before upgrading and retain the backup for downgrade.

The `ex_` table prefix preserves the native ExProtect format lineage. It is not a CoreProtect `co_` schema or an automatic importer.

- Metadata/migration/sequence tables track format, clean shutdown and portable identities.
- Mapping tables store dimensions, resources, block-state properties and actors.
- Block history stores event identity, time, actor, position, before/after state and payload, action/cause and rollback state.
- Container history stores correlated immutable slot changes with item counts and persistent component payloads. Logical owner indexes support checking history omitted by filters.
- Item recovery tables retain operation records, entries, owner/transaction claims, unresolved protection and complete saved images/reconciliation decisions. These do not expose item apply.

Block rollback state values are `0 ACTIVE`, `1 ROLLED_BACK`, `2 PENDING`. Pending rows retain interrupted reconciliation work; queries excluding rolled-back rows still retain pending rows. Newly claimed untouched active rows can be released after permission revocation, while older pending recovery state is preserved.

Schema 7 introduced item journals/claims, 8 added owner history indexing, 9 added durable unresolved protection and 10 added complete owner images and reconciliation acknowledgement. Existing block payloads and older supported item encodings remain readable. Do not manually delete recovery tables/claims to bypass refusal.

Stop the server before a simple database-file backup. Default SQLite location is `guardian/guardian.sqlite` under the server directory. Backend selection does not migrate history to another database.
