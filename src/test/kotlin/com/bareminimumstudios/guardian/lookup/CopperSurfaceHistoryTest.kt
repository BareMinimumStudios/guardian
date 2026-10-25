package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.platform.minecraft.CopperSurfaceHistory
import kotlin.test.*
import org.junit.jupiter.api.Test

class CopperSurfaceHistoryTest {
    companion object { init { net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap() } }
    private fun state(id:String)=BlockStateSnapshot(ResourceId.parse(id))
    @Test fun waxAndWaxRemovalCoverCopperShapesAndFunctionalBlocks() {
        for(id in listOf("copper_block","cut_copper","cut_copper_stairs","copper_grate","copper_bulb","copper_door","copper_trapdoor")) {
            val waxed=if(id=="copper_block")"waxed_copper_block" else "waxed_"+id
            assertEquals("waxed minecraft:"+id,CopperSurfaceHistory.describe(state(id),state(waxed)))
            assertEquals("scraped wax from minecraft:"+waxed,CopperSurfaceHistory.describe(state(waxed),state(id)))
        }
    }
    @Test fun oxidationScrapingHasItsOwnDescriptionAndRejectsNaturalOxidation() {
        assertEquals("scraped oxidation from minecraft:oxidized_copper",CopperSurfaceHistory.describe(state("oxidized_copper"),state("weathered_copper")))
        assertNull(CopperSurfaceHistory.describe(state("weathered_copper"),state("oxidized_copper")))
        assertNull(CopperSurfaceHistory.describe(state("copper_block"),state("copper_block")))
        assertNull(CopperSurfaceHistory.describe(state("oak_log"),state("stripped_oak_log")))
    }
}
