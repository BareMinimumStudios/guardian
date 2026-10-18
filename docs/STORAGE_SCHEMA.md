# Guardian storage schema

Current schema: **12**. `GuardianSchema` and `SchemaMigrator` in `common` are authoritative for migrations. Startup upgrades supported older databases; newer unsupported schemas are refused. Back up before upgrading and retain the backup for downgrade.

The `ex_` table prefix preserves the native ExProtect format lineage. It is not a CoreProtect `co_` schema or an automatic importer.

- Metadata/migration/sequence tables track format, clean shutdown and portable identities.
- Mapping tables store dimensions, resources, block-state properties and actors.
- Block history stores event identity, time, actor, position, before/after state and payload, action/cause and rollback state.
- Container history stores correlated immutable slot changes with item counts and persistent component payloads. Logical owner indexes support checking history omitted by filters.
- Item recovery tables retain operation records, entries, owner/transaction claims, unresolved protection and complete saved images/reconciliation decisions. These do not expose item apply.

Block rollback state values are `0 ACTIVE`, `1 ROLLED_BACK`, `2 PENDING`. Pending rows retain interrupted reconciliation work; queries excluding rolled-back rows still retain pending rows. Newly claimed untouched active rows can be released after permission revocation, while older pending recovery state is preserved.

Schema 7 introduced item journals/claims, 8 added owner history indexing, 9 added durable unresolved protection and 10 added complete owner images and reconciliation acknowledgement. Existing block payloads and older supported item encodings remain readable. Do not manually delete recovery tables/claims to bypass refusal.

Stop the server before a simple database-file backup. Default SQLite location is `guardian/guardian.sqlite` under the server directory. Backend selection does not migrate history to another database.

Schema 11 adds `ex_player_activity`: unique event UUID, timestamp, player UUID, immutable event-time name, CHAT/LOGIN/LOGOUT kind and nullable plain chat text. Kind/time and UUID/time indexes support bounded lookups. Retries ignore an existing event UUID; activity writes share the audit batch transaction. These records do not participate in block/item rollback.

Schema 12 adds derived `ex_container_item` rows keyed by transaction UUID and resource ID, with a resource/transaction index. It indexes all recorded nonempty before/after stack identities, not display names or component contents. A keyset backfill closes each read cursor before batch writes and preserves existing payload bytes. Resource conditions use EXISTS/NOT EXISTS before limit/offset, avoiding duplicate transaction rows.
