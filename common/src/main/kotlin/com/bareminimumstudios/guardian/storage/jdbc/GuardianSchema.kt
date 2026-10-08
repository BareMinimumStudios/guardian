package com.bareminimumstudios.guardian.storage.jdbc

internal object GuardianSchema {
    const val CURRENT_VERSION = 8

    val migrations: List<SchemaMigration> = listOf(
        SchemaMigration(
            version = 1,
            description = "Initial Fabric-native block audit schema",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS ex_meta (
                    meta_key VARCHAR PRIMARY KEY,
                    meta_value VARCHAR NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS ex_sequences (
                    sequence_name VARCHAR PRIMARY KEY,
                    next_value BIGINT NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS ex_world_map (
                    id INTEGER PRIMARY KEY,
                    world VARCHAR NOT NULL UNIQUE
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS ex_resource_map (
                    id INTEGER PRIMARY KEY,
                    resource VARCHAR NOT NULL UNIQUE
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS ex_blockdata_map (
                    id INTEGER PRIMARY KEY,
                    data VARCHAR NOT NULL UNIQUE
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS ex_actor_map (
                    id BIGINT PRIMARY KEY,
                    kind SMALLINT NOT NULL,
                    uuid VARCHAR,
                    name VARCHAR,
                    entity_type VARCHAR,
                    source VARCHAR,
                    fingerprint VARCHAR NOT NULL UNIQUE
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS ex_block (
                    rowid BIGINT PRIMARY KEY,
                    event_uuid VARCHAR NOT NULL UNIQUE,
                    time BIGINT NOT NULL,
                    actor BIGINT,
                    wid INTEGER NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    before_type INTEGER NOT NULL,
                    before_data INTEGER NOT NULL,
                    before_meta BLOB,
                    after_type INTEGER NOT NULL,
                    after_data INTEGER NOT NULL,
                    after_meta BLOB,
                    cause SMALLINT NOT NULL,
                    action SMALLINT NOT NULL,
                    rolled_back SMALLINT NOT NULL DEFAULT 0
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS ex_block_time_idx ON ex_block(time)",
                "CREATE INDEX IF NOT EXISTS ex_block_location_idx ON ex_block(wid, x, y, z, time)",
                "CREATE INDEX IF NOT EXISTS ex_block_actor_idx ON ex_block(actor, time)",
                "CREATE INDEX IF NOT EXISTS ex_block_action_idx ON ex_block(action, time)"
            )
        ),
        SchemaMigration(2, "Correlated container item transactions", listOf(
            "CREATE TABLE ex_container (transaction_uuid VARCHAR PRIMARY KEY, time BIGINT NOT NULL, actor BIGINT NOT NULL, menu_id INTEGER NOT NULL, interaction VARCHAR NOT NULL, changes BLOB NOT NULL)",
            "CREATE TABLE ex_container_location (transaction_uuid VARCHAR NOT NULL, wid INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, PRIMARY KEY(transaction_uuid, wid, x, y, z))",
            "CREATE INDEX ex_container_time_idx ON ex_container(time)",
            "CREATE INDEX ex_container_actor_idx ON ex_container(actor, time)",
            "CREATE INDEX ex_container_location_idx ON ex_container_location(wid, x, y, z)"
        )),
        SchemaMigration(3, "Close and standalone player item action kinds", emptyList()),
        // New persisted enum names must not reach readers that predate these actions.
        SchemaMigration(4, "Accepted creative inventory action kind", emptyList()),
        SchemaMigration(5, "System actors and block hopper transfers", emptyList()),
        SchemaMigration(6, "Transient crafting grids and correlated crafting actions", emptyList()),
        SchemaMigration(7, "Persistent item rollback journal and exclusive recovery claims", listOf(
            "CREATE TABLE ex_item_rollback (operation_uuid VARCHAR PRIMARY KEY, created_at BIGINT NOT NULL, phase VARCHAR NOT NULL)",
            "CREATE TABLE ex_item_rollback_entry (operation_uuid VARCHAR NOT NULL, entry_index INTEGER NOT NULL, transaction_uuid VARCHAR NOT NULL, changes BLOB NOT NULL, PRIMARY KEY(operation_uuid, entry_index))",
            "CREATE TABLE ex_item_rollback_owner (owner_key VARCHAR PRIMARY KEY, operation_uuid VARCHAR NOT NULL)",
            "CREATE TABLE ex_item_rollback_claim (transaction_uuid VARCHAR PRIMARY KEY, operation_uuid VARCHAR NOT NULL)",
            "CREATE INDEX ex_item_rollback_phase_idx ON ex_item_rollback(phase, created_at)",
            "CREATE INDEX ex_item_rollback_owner_operation_idx ON ex_item_rollback_owner(operation_uuid)",
            "CREATE INDEX ex_item_rollback_claim_operation_idx ON ex_item_rollback_claim(operation_uuid)"
        )),
        SchemaMigration(8, "Logical inventory owner index for filtered rollback safety", listOf(
            "CREATE TABLE ex_container_owner (transaction_uuid VARCHAR NOT NULL, owner_key VARCHAR NOT NULL, time BIGINT NOT NULL, PRIMARY KEY(transaction_uuid, owner_key))",
            "CREATE INDEX ex_container_owner_time_idx ON ex_container_owner(owner_key, time)"
        ), ContainerOwnerIndex::backfill)
    )
}
