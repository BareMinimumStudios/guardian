# Guardian native storage schema v1

The Fabric-native schema remains at version 1 in Step 2C. No table migration is required because the original `rolled_back SMALLINT` column can safely represent the expanded rollback journal states.

## Metadata

- `ex_schema_migrations` — applied schema migrations
- `ex_meta` — storage format, clean shutdown marker, schema and codec metadata
- `ex_sequences` — portable ID allocation shared by SQLite and DuckDB

## Mapping tables

- `ex_world_map` — namespaced dimension IDs
- `ex_resource_map` — namespaced block/resource IDs
- `ex_blockdata_map` — canonical encoded block-state property maps
- `ex_actor_map` — players, entities and system actors

## `ex_block`

Each block row stores:

- stable `rowid`
- unique `event_uuid`
- millisecond timestamp
- actor reference
- dimension reference
- x/y/z
- before block resource/state/payload
- after block resource/state/payload
- stable cause code
- stable action code
- rollback journal state

Rollback state values:

```text
0 ACTIVE       normal history row, eligible for rollback
1 ROLLED_BACK  rollback completed/finalized
2 PENDING      rollback batch was claimed; reconcile live state before retrying
```

Queries with `includeRolledBack=false` exclude state 1 but intentionally retain state 2 so interrupted batches can be reconciled.

## Indexes

v1 indexes:

- timestamp
- dimension + position + timestamp
- actor + timestamp
- action + timestamp

Step 2C radius queries currently use bounded x/y/z ranges (a cuboid radius). Additional chunk/spatial indexing should be driven by profiling rather than guessed prematurely.

## Identity and retry behavior

`rowid` is monotonic allocation identity and may contain gaps. `event_uuid` is the durable event identity; its unique constraint and conflict-ignore insert make writer retry idempotent.

## Compatibility strategy

This is intentionally not a byte-for-byte CoreProtect schema clone. A later importer can translate compatible `co_*` history into Guardian-native rows without requiring new Fabric records to inherit Bukkit serialization.
