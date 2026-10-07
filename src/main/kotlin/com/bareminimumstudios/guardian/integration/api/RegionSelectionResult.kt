package com.bareminimumstudios.guardian.integration.api

sealed interface RegionSelectionResult {
    data class Success(val selection: RegionSelection) : RegionSelectionResult
    data class Failure(val message: String) : RegionSelectionResult
}
