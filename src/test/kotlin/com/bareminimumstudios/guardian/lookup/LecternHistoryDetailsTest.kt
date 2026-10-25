package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.platform.minecraft.MinecraftNbtPayloadCodec
import net.minecraft.nbt.CompoundTag
import java.util.UUID
import kotlin.test.*

class LecternHistoryDetailsTest {
    private fun state(book:Boolean,page:Int=0)=BlockStateSnapshot(ResourceId.parse("minecraft:lectern"),emptyMap(),
        MinecraftNbtPayloadCodec.encode(CompoundTag().also { tag->if(book) { tag.put("Book",CompoundTag().also { it.putString("id","minecraft:written_book") });tag.putInt("Page",page) } }))
    private fun row(before:BlockStateSnapshot,after:BlockStateSnapshot)=BlockChangeSnapshot(1,ActorIdentity.Player(UUID.randomUUID(),"Alice"),ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,1),before,after,ChangeCause.LECTERN,ActionType.BLOCK_CHANGE)
    @Test fun summarizesOnlyAcceptedBookAndPageState() {
        assertEquals("inserted a lectern book",LecternHistoryDetails.summary(row(state(false),state(true))))
        assertEquals("removed a lectern book",LecternHistoryDetails.summary(row(state(true),state(false))))
        assertEquals("turned lectern page 1 -> 2",LecternHistoryDetails.summary(row(state(true),state(true,1))))
        assertEquals("opened a lectern book",LecternHistoryDetails.summary(row(state(true),state(true))))
    }
}
