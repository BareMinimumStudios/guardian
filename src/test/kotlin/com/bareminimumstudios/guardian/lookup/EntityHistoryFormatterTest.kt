package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import java.util.UUID
import kotlin.test.*

class EntityHistoryFormatterTest {
    companion object { init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() } }
    private val row=EntityAuditEvent(timestampEpochMillis=1000,actor=ActorIdentity.System("environment"),dimension=ResourceId.parse("minecraft:overworld"),position=BlockPosition(1,64,2),targetUuid=UUID.randomUUID(),targetType=ResourceId.parse("example:deer"),kind=EntityAuditKind.DEATH,damageType=ResourceId.parse("minecraft:fall"))
    @Test fun deathKeepsIdentityAndDamageInHoverAndOffersTargetSearch() {
        val line=EntityHistoryFormatter.line(row,2000)
        assertTrue("example:deer died; source: environment @ 1, 64, 2" in line.string)
        assertFalse(row.targetUuid.toString() in line.string)
        val hover=line.style.hoverEvent!!.getValue(HoverEvent.Action.SHOW_TEXT)!!.string
        assertTrue(row.targetUuid.toString() in hover);assertTrue("minecraft:fall" in hover)
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND,line.style.clickEvent!!.action)
        assertEquals("/guardian entities id:${row.targetUuid}",line.style.clickEvent!!.value)
    }
    @Test fun renameUsesLiteralSanitizedNamesAndLabelsUnknownAttribution() {
        val line=EntityHistoryFormatter.line(row.copy(kind=EntityAuditKind.NAME_CHANGE,actor=ActorIdentity.Unknown,beforeName="Old§c\u202ename",afterName="New",damageType=null),2000)
        assertTrue("unknown actor renamed" in line.string);assertTrue("New" in line.string)
        assertFalse('§' in line.string);assertFalse('\u202e' in line.string)
    }
    @Test fun removingACustomNameIsDifferentFromDeath() {
        val line=EntityHistoryFormatter.line(row.copy(kind=EntityAuditKind.NAME_CHANGE,beforeName="Old",afterName=null,damageType=null),2000).string
        assertTrue("to no custom name" in line);assertFalse("died" in line)
    }
}
