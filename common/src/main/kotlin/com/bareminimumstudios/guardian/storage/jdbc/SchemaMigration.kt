package com.bareminimumstudios.guardian.storage.jdbc

import java.sql.Connection

internal data class SchemaMigration(
    val version: Int,
    val description: String,
    val statements: List<String>,
    val dataMigration: ((Connection) -> Unit)? = null
)
