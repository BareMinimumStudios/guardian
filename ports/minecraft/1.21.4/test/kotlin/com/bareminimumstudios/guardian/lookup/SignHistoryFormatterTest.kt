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
    private fun appearance(base:BlockStateSnapshot,waxed:Boolean=false,color:net.minecraft.world.item.DyeColor=net.minecraft.world.item.DyeColor.BLACK,glowing:Boolean=false):BlockStateSnapshot {
        val tag=MinecraftNbtPayloadCodec.decode(requireNotNull(base.blockEntityData))
        val text=SignText.DIRECT_CODEC.parse(NbtOps.INSTANCE,tag.getCompound("front_text")).getOrThrow().setColor(color).setHasGlowingText(glowing)
        tag.put("front_text",SignText.DIRECT_CODEC.encodeStart(NbtOps.INSTANCE,text).getOrThrow());tag.putBoolean("is_waxed",waxed)
        return BlockStateSnapshot(base.blockId,base.properties,MinecraftNbtPayloadCodec.encode(tag))
    }
    @Test fun waxAndUnwaxAreNotReportedAsTextEdits() {
        val a=state("same");val b=appearance(a,waxed=true)
        assertEquals("waxed sign",SignHistoryDetails.summary(row(a,b)))
        assertEquals("removed sign wax",SignHistoryDetails.summary(row(b,a)))
        assertEquals(emptyList(),SignHistoryDetails.sides(row(a,b)))
    }
    @Test fun colorAndGlowIdentifyTheSideAndActualResult() {
        val a=state("same");val b=appearance(a,color=net.minecraft.world.item.DyeColor.RED,glowing=true)
        assertEquals("dyed front sign text red; made front sign text glow",SignHistoryDetails.summary(row(a,b)))
        assertTrue("removed front sign text glow" in SignHistoryDetails.summary(row(b,a)))
        assertFalse("edited" in SignHistoryDetails.summary(row(a,b)))
        assertFalse(SignHistoryDetails.changed(b,b))
    }
    private fun post(raw:String,filtered:String):BlockStateSnapshot {
        fun panel(value:String,view:String):CompoundTag {
            val holder=CompoundTag();holder.putString("color","black")
            holder.put("message",net.minecraft.network.chat.ComponentSerialization.CODEC.listOf().encodeStart(NbtOps.INSTANCE,listOf(Component.literal(value))).getOrThrow())
            holder.put("filtered_message",net.minecraft.network.chat.ComponentSerialization.CODEC.listOf().encodeStart(NbtOps.INSTANCE,listOf(Component.literal(view))).getOrThrow())
            return CompoundTag().also { it.put("TextHolder",holder) }
        }
        val tag=CompoundTag();tag.put("SignUp",panel(raw,filtered));tag.put("SignDown",panel("",""))
        return BlockStateSnapshot(ResourceId.parse("supplementaries:way_sign"),emptyMap(),MinecraftNbtPayloadCodec.encode(tag))
    }
    @Test fun customSignpostDetailsUseFilteredPlainText() {
        val a=post("old secret","old visible");val b=post("new secret","new visible")
        assertTrue(SignHistoryDetails.changed(a,b));assertEquals("edited upper sign text",SignHistoryDetails.summary(row(a,b)))
        assertTrue("new visible" in SignHistoryDetails.hover(row(a,b)));assertFalse("secret" in SignHistoryDetails.hover(row(a,b)))
    }
    private fun plaque(raw:String,filtered:String=raw,waxed:Boolean=false,color:String="black",glow:Boolean=false):BlockStateSnapshot {
        val holder=CompoundTag()
        fun encoded(value:String)=net.minecraft.network.chat.ComponentSerialization.CODEC.listOf().encodeStart(NbtOps.INSTANCE,listOf(Component.literal(value))).getOrThrow()
        holder.put("message",encoded(raw));holder.put("filtered_message",encoded(filtered))
        holder.putString("color",color);holder.putBoolean("has_glowing_text",glow)
        val tag=CompoundTag();tag.put("TextHolder",holder);tag.putBoolean("Waxed",waxed)
        return BlockStateSnapshot(ResourceId.parse("suppsquared:iron_plaque"),emptyMap(),MinecraftNbtPayloadCodec.encode(tag))
    }
    @Test fun plaquesUseTheirOwnNamespaceAndFilteredText() {
        val change=row(plaque("old secret","old visible"),plaque("new secret","new visible"))
        assertEquals("edited plaque text",SignHistoryDetails.summary(change))
        assertTrue("new visible" in SignHistoryDetails.hover(change));assertFalse("secret" in SignHistoryDetails.hover(change))
    }
    @Test fun plaqueAppearanceChangesHaveDistinctDescriptions() {
        val a=plaque("same");val b=plaque("same",waxed=true,color="red",glow=true)
        assertEquals("waxed plaque; dyed plaque text red; made plaque text glow",SignHistoryDetails.summary(row(a,b)))
        assertTrue("removed plaque wax" in SignHistoryDetails.summary(row(b,a)))
        assertFalse(SignHistoryDetails.changed(b,b))
    }
    @Test fun unrelatedNamespacesDoNotInheritPlaquePayloadParsing() {
        val original=plaque("same")
        val unrelated=BlockStateSnapshot(ResourceId.parse("fixture:unknown"),original.properties,original.blockEntityData)
        assertTrue("details unavailable" in SignHistoryDetails.summary(row(unrelated,unrelated)))
    }
}
