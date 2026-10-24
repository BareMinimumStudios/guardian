package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import java.util.UUID
import kotlin.test.*

class ItemFrameHistoryFormatterTest {
    companion object { init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() } }
    private val empty=ItemFrameSnapshot(ItemStackSnapshot.EMPTY,0)
    private val full=ItemFrameSnapshot(ItemStackSnapshot(ResourceId.parse("minecraft:diamond"),1,BinaryPayload.of(byteArrayOf(1))),0)
    private fun event(kind:EntityAuditKind,before:ItemFrameSnapshot?,after:ItemFrameSnapshot?)=EntityAuditEvent(timestampEpochMillis=1000,actor=ActorIdentity.Player(UUID.randomUUID(),"Alex"),dimension=ResourceId.parse("minecraft:overworld"),position=BlockPosition(1,64,2),targetUuid=UUID.randomUUID(),targetType=ResourceId.parse("minecraft:glow_item_frame"),kind=kind,frameBefore=before,frameAfter=after)
    @Test fun insertionAndRemovalDescribeActualDisplayItem() {
        assertTrue("put minecraft:diamond" in EntityHistoryFormatter.line(event(EntityAuditKind.FRAME_CHANGE,empty,full)).string)
        assertTrue("removed minecraft:diamond from" in EntityHistoryFormatter.line(event(EntityAuditKind.FRAME_CHANGE,full,empty)).string)
    }
    @Test fun rotationDoesNotClaimAnItemWasTransferred() {
        val line=EntityHistoryFormatter.line(event(EntityAuditKind.FRAME_CHANGE,full,full.copy(rotation=1))).string
        assertTrue("rotated" in line);assertFalse("put" in line)
    }
    @Test fun brokenFrameRetainsDisplayedItemDescription() {
        assertTrue("removed minecraft:glow_item_frame displaying minecraft:diamond" in EntityHistoryFormatter.line(event(EntityAuditKind.FRAME_REMOVE,full,null)).string)
    }
}
