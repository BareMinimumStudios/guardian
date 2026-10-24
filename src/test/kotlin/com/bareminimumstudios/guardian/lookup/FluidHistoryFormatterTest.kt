package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.network.chat.HoverEvent
import java.util.UUID
import kotlin.test.*

class FluidHistoryFormatterTest {
    companion object { init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() } }
    private val empty=FluidSnapshot(FluidSnapshot.EMPTY,false,ResourceId.parse("minecraft:oak_slab"))
    private val water=empty.copy(fluidId=ResourceId.parse("minecraft:water"),source=true)
    private val row=FluidAuditEvent(timestampEpochMillis=1000,actor=ActorIdentity.Player(UUID.randomUUID(),"Alex"),dimension=ResourceId.parse("minecraft:overworld"),position=BlockPosition(1,64,2),kind=FluidAuditKind.PLACE,before=empty,after=water)
    @Test fun placementDescribesBucketAndHoverHostWithoutClaimingBlockRollback() {
        val line=FluidHistoryFormatter.line(row,2000);assertTrue("Alex placed minecraft:water with a bucket" in line.string)
        assertTrue("minecraft:oak_slab" in line.style.hoverEvent!!.getValue(HoverEvent.Action.SHOW_TEXT)!!.string)
        assertFalse("rolled" in line.string)
    }
    @Test fun naturalFlowDoesNotClaimABucketOrKnownPlayer() {
        val natural=row.copy(actor=ActorIdentity.Unknown,kind=FluidAuditKind.FLOW)
        val text=FluidHistoryFormatter.line(natural,2000).string
        assertTrue("environment/unknown" in text);assertTrue("fluid changed" in text);assertFalse("bucket" in text)
    }
    @Test fun formationNamesSolidBlockAndShowsAmounts() {
        val form=row.copy(kind=FluidAuditKind.FORM,before=water.copy(amount=8),after=empty.copy(blockId=ResourceId.parse("minecraft:obsidian"),amount=0))
        val line=FluidHistoryFormatter.line(form,2000);assertTrue("formed minecraft:obsidian" in line.string)
        assertTrue("amount=8" in line.style.hoverEvent!!.getValue(HoverEvent.Action.SHOW_TEXT)!!.string)
    }
    @Test fun removalDescribesCollectionRatherThanPlacingAir() {
        val line=FluidHistoryFormatter.line(row.copy(kind=FluidAuditKind.REMOVE,before=water,after=empty),2000).string
        assertTrue("collected minecraft:water" in line);assertFalse("placed minecraft:empty" in line)
    }
}
