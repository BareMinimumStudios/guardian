package com.bareminimumstudios.guardian.storage.jdbc

internal data class SchemaMigration(
    val version: Int,
    val description: String,
    val statements: List<String>,
    val dataMigration: ((java.sql.Connection) -> Unit)? = null
)
