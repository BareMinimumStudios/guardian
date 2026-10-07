package com.bareminimumstudios.guardian.integration

data class IntegrationDescriptor(
    val id: String,
    val modId: String,
    val displayName: String
)

data class IntegrationStatus(
    val descriptor: IntegrationDescriptor,
    val present: Boolean,
    val implemented: Boolean
)
