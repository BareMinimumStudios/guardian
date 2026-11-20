package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.network.chat.HoverEvent
import java.util.UUID
import kotlin.test.*

class TamingBreedingFormatterTest {
    companion object { init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() } }
    private val tame=EntityAuditEvent(timestampEpochMillis=1000,actor=ActorIdentity.Player(UUID.randomUUID(),"Alex"),dimension=ResourceId.parse("minecraft:overworld"),position=BlockPosition(1,64,2),targetUuid=UUID.randomUUID(),targetType=ResourceId.parse("minecraft:wolf"),kind=EntityAuditKind.TAME)
    @Test fun tameDescribesCompletedOwnershipWithoutCallingItARename() {
        val line=EntityHistoryFormatter.line(tame,2000).string
        assertTrue("Alex tamed minecraft:wolf" in line);assertFalse("renamed" in line)
    }
    @Test fun bornShowsChildAndCreditWithParentsInHover() {
        val born=tame.copy(kind=EntityAuditKind.BREED,parentUuid=UUID.randomUUID(),partnerUuid=UUID.randomUUID())
        val line=EntityHistoryFormatter.line(born,2000)
        assertTrue("was born; breeding credited to Alex" in line.string)
        val hover=line.style.hoverEvent!!.getValue(HoverEvent.Action.SHOW_TEXT)!!.string
        assertTrue(born.parentUuid.toString() in hover);assertTrue(born.partnerUuid.toString() in hover)
        assertFalse(born.parentUuid.toString() in line.string)
    }
}
