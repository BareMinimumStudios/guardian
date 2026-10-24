package com.bareminimumstudios.guardian.lookup
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.query.*
import kotlin.test.*
import org.junit.jupiter.api.Test
class EnvironmentHistoryFormatterTest {
    private fun text(before:String,after:String,action:ActionType,cause:ChangeCause=ChangeCause.FIRE):String {
        val row=BlockChangeSnapshot(1,ActorIdentity.Unknown,ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,2),BlockStateSnapshot(ResourceId.parse(before)),BlockStateSnapshot(ResourceId.parse(after)),cause,action)
        return BlockHistoryFormatter.lines(listOf(StoredBlockChange(1,row,BlockRollbackState.ACTIVE)),1000)[1].string
    }
    @Test fun fireIgnitionBurningAndExtinguishingAreDistinct() {
        assertTrue("ignited minecraft:fire" in text("minecraft:air","minecraft:fire",ActionType.BLOCK_PLACE))
        assertTrue("extinguished minecraft:fire" in text("minecraft:fire","minecraft:air",ActionType.BLOCK_BREAK))
        assertTrue("burned minecraft:oak_planks" in text("minecraft:oak_planks","minecraft:air",ActionType.BLOCK_BREAK))
    }
    @Test fun explosionKeepsItsCauseAndDoesNotInventPlayerCredit() {
        val line=text("minecraft:stone","minecraft:air",ActionType.BLOCK_BREAK,ChangeCause.EXPLOSION)
        assertTrue("unknown" in line);assertTrue("via explosion" in line)
    }
}
