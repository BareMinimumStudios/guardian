package com.bareminimumstudios.guardian.lookup
import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.query.*
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftNbtPayloadCodec
import net.minecraft.nbt.*
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ClickEvent
import net.minecraft.world.level.block.entity.SignText
import kotlin.test.*
import org.junit.jupiter.api.Test
class SignHistoryFormatterTest {
    companion object { init { net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap() } }
    private fun state(front:String,back:String="",filtered:String=front):BlockStateSnapshot {
        fun text(raw:String,view:String)=SignText().setMessage(0,Component.literal(raw).withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND,"/op attacker")) },Component.literal(view))
        val tag=CompoundTag();tag.put("front_text",SignText.DIRECT_CODEC.encodeStart(NbtOps.INSTANCE,text(front,filtered)).getOrThrow());tag.put("back_text",SignText.DIRECT_CODEC.encodeStart(NbtOps.INSTANCE,text(back,back)).getOrThrow())
        return BlockStateSnapshot(ResourceId.parse("oak_sign"),emptyMap(),MinecraftNbtPayloadCodec.encode(tag))
    }
    private fun row(a:BlockStateSnapshot,b:BlockStateSnapshot)=BlockChangeSnapshot(1,ActorIdentity.Unknown,ResourceId.parse("overworld"),BlockPosition(1,64,2),a,b,ChangeCause.SIGN_TEXT,ActionType.BLOCK_CHANGE)
    @Test fun frontAndBackTextUseExplicitEditingWords() {
        assertEquals("edited front sign text",SignHistoryDetails.summary(row(state("old"),state("new"))))
        assertEquals("edited back sign text",SignHistoryDetails.summary(row(state("same","old"),state("same","new"))))
    }
    @Test fun hoverUsesFilteredLiteralsWithoutInheritedSignCommandsOrFormatting() {
        val s=row(state("old secret",filtered="old filtered"),state("new secret",filtered="new filtered"))
        val line=BlockHistoryFormatter.lines(listOf(StoredBlockChange(1,s,BlockRollbackState.ACTIVE)),1000)[1]
        assertTrue("edited front sign text" in line.string);assertFalse("placed" in line.string)
        val hover=line.style.hoverEvent!!.getValue(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT)!!
        assertTrue("old filtered" in hover.string && "new filtered" in hover.string);assertFalse("secret" in hover.string)
        assertNull(line.style.clickEvent);assertNull(hover.style.clickEvent);assertTrue(hover.siblings.isEmpty())
    }
    @Test fun malformedDetailsFailClosedAndRetainedRowsStayReadable() {
        val s=row(BlockStateSnapshot(ResourceId.parse("oak_sign")),BlockStateSnapshot(ResourceId.parse("oak_sign")))
        assertTrue("details unavailable" in SignHistoryDetails.summary(s));assertEquals("Sign text details unavailable",SignHistoryDetails.hover(s))
    }
}
