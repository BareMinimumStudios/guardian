package com.bareminimumstudios.guardian.integration.api

import com.bareminimumstudios.guardian.domain.BlockBounds
import com.bareminimumstudios.guardian.domain.ResourceId

data class RegionSelection(
    val providerId: String,
    val dimension: ResourceId,
    val bounds: BlockBounds
)
