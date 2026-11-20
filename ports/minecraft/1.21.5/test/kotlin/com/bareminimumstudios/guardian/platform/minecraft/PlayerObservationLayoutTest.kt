package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.rollback.MinecraftInventoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerObservationLayoutTest {
    @Test fun observationMatchesTheActualVanillaInventoryLayout() {
        MinecraftTestBootstrap.initialize()
        val inventory=InventoryMenuFixtures.inventory()
        assertEquals(inventory.containerSize,MinecraftInventoryObservation.PLAYER_SLOT_COUNT)
    }
}
