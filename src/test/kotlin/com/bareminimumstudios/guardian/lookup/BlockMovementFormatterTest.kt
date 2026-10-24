package com.bareminimumstudios.guardian.lookup
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.query.*
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
class BlockMovementFormatterTest {
    companion object { init { net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap() } }
    private val id=UUID.randomUUID();private val air=BlockStateSnapshot(ResourceId.parse("minecraft:air"));private val sand=BlockStateSnapshot(ResourceId.parse("minecraft:sand"))
    private fun row(phase:BlockMovementPhase)=StoredBlockChange(1,BlockChangeSnapshot(1,ActorIdentity.Unknown,ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,2),if(phase==BlockMovementPhase.FALL_START)sand else air,if(phase==BlockMovementPhase.FALL_START)air else sand,if(phase==BlockMovementPhase.PISTON)ChangeCause.PISTON else ChangeCause.GRAVITY,ActionType.BLOCK_CHANGE,movementId=id,movementPhase=phase),BlockRollbackState.ACTIVE)
    @Test fun startDoesNotClaimDeliveryAndLandingIsExplicit() {
        val start=BlockHistoryFormatter.lines(listOf(row(BlockMovementPhase.FALL_START)),1000)[1]
        assertTrue("began falling" in start.string);assertFalse("landed" in start.string)
        val land=BlockHistoryFormatter.lines(listOf(row(BlockMovementPhase.FALL_LAND)),1000)[1];assertTrue("landed" in land.string)
        assertEquals("/guardian lookup m:$id",land.style.clickEvent!!.value)
    }
    @Test fun settledPistonRowExposesTheFullCorrelationOnHover() {
        val line=BlockHistoryFormatter.lines(listOf(row(BlockMovementPhase.PISTON)),1000)[1]
        assertTrue("piston changed" in line.string)
        assertTrue(id.toString() in line.style.hoverEvent!!.getValue(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT)!!.string)
    }
}
