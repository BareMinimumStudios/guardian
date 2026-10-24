package com.bareminimumstudios.guardian.lookup
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.query.*
import kotlin.test.*
import org.junit.jupiter.api.Test
class GrowthDecayFormatterTest {
    private fun text(before:BlockStateSnapshot,after:BlockStateSnapshot,cause:ChangeCause):String {
        val row=BlockChangeSnapshot(1,ActorIdentity.Unknown,ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,2),before,after,cause,ActionType.BLOCK_CHANGE)
        return BlockHistoryFormatter.lines(listOf(StoredBlockChange(1,row,BlockRollbackState.ACTIVE)),1000)[1].string
    }
    @Test fun cropGrowthShowsAgeChangeRatherThanASecondPlacement() {
        val crop=BlockStateSnapshot(ResourceId.parse("minecraft:wheat"),mapOf("age" to "0"))
        val line=text(crop,BlockStateSnapshot(crop.blockId,mapOf("age" to "1")),ChangeCause.GROWTH)
        assertTrue("age 0 -> 1" in line);assertFalse("placed" in line);assertTrue("unknown" in line)
    }
    @Test fun decayNamesTheLeavesAndDoesNotClaimAPlayerBreak() {
        val line=text(BlockStateSnapshot(ResourceId.parse("minecraft:oak_leaves")),BlockStateSnapshot(ResourceId.parse("minecraft:air")),ChangeCause.DECAY)
        assertTrue("decayed minecraft:oak_leaves" in line);assertFalse("broke" in line)
    }
}
