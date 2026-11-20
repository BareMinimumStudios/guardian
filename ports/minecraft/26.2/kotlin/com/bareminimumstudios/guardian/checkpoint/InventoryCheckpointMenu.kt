package com.bareminimumstudios.guardian.checkpoint

import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.*
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/** Full saved inventory, with independent virtual slots and no historical component decoding. */
internal class InventoryCheckpointMenu(
    id:Int,private val viewer:ServerPlayer,private val checkpoint:InventoryCheckpoint,
    private val permitted:()->Boolean,private val preview:()->Unit
) : AbstractContainerMenu(MenuType.GENERIC_9x6,id) {
    private val display=SimpleContainer(90)
    private var closed=false
    private var resync=false
    init {
        for(i in 0 until 90)addSlot(object:Slot(display,i,8+(i%9)*18,18+(i/9)*18) {
            override fun mayPlace(stack:ItemStack)=false
            override fun mayPickup(player:Player)=false
        })
        for(i in 0 until 54)display.setItem(i,card(Items.STAINED_GLASS_PANE.gray()," "))
        checkpoint.inventory.slots.entries.sortedBy { it.key.index }.forEach { (address,snapshot) ->
            val slot=address.index
            val target=if(slot<9)27+slot else if(slot<36)slot-9 else slot
            val icon=if(snapshot.isEmpty)Items.STAINED_GLASS_PANE.lightGray() else CheckpointLinks.icon(snapshot.itemId)
            display.setItem(target,card(icon,if(snapshot.isEmpty)"Slot $slot: empty" else "Slot $slot: ${snapshot.count} ${snapshot.itemId!!.path.replace('_',' ')}",
                listOf("Item: ${snapshot.itemId ?: "empty"}","Count: ${snapshot.count}","Plain icon; saved components are retained but not rendered")))
        }
        display.setItem(45,card(Items.BOOK,checkpoint.playerName,listOf("Player UUID: ${checkpoint.playerId}","Checkpoint: ${checkpoint.checkpointId}","Minecraft: ${checkpoint.minecraftVersion}")))
        display.setItem(49,card(Items.PAPER,"${java.time.Instant.ofEpochMilli(checkpoint.capturedAt)}",listOf("${checkpoint.reason.name.lowercase().replace('_',' ')} | ${checkpoint.inventory.slots.size} persistent slots","Top rows: main inventory, then hotbar and equipment","Your inventory below is also read-only")))
        display.setItem(52,card(Items.COMPASS,"Preview restoration",listOf("Closes this viewer and checks eligibility","Does not change items; separate apply/confirmation required","Requires guardian.inventory.restore")))
        display.setItem(53,card(Items.BARRIER,"Close"))
    }
    override fun stillValid(player:Player)=!closed && player===viewer && permitted()
    override fun quickMoveStack(player:Player,index:Int):ItemStack=ItemStack.EMPTY
    override fun canDragTo(slot:Slot)=false
    override fun canTakeItemForPickAll(stack:ItemStack,slot:Slot)=false
    override fun getCarried():ItemStack=ItemStack.EMPTY
    override fun setCarried(stack:ItemStack) { /* Historical icons never become cursor items. */ }
    override fun removed(player:Player) { closed=true;display.clearContent() }
    override fun clicked(index:Int,button:Int,type:ContainerInput,player:Player) {
        if(player!==viewer)return
        if(!stillValid(player)) { viewer.closeContainer();return }
        resync=true
        // Never enter vanilla's mutation machinery, including outside and bottom slots.
        if(type!=ContainerInput.PICKUP || button!=0)return
        if(index==53)viewer.closeContainer()
        else if(index==52) { viewer.closeContainer();preview() }
    }
    override fun broadcastChanges() {
        if(viewer.containerMenu===this && !stillValid(viewer)) { viewer.closeContainer();return }
        for(i in 0 until 36) {
            val current=viewer.inventory.getItem(if(i<27)i+9 else i-27)
            if(!ItemStack.matches(current,display.getItem(54+i)))display.setItem(54+i,current.copy())
        }
        if(resync) { resync=false;super.broadcastFullState() } else super.broadcastChanges()
    }
    private fun card(item:Item,title:String,lines:List<String> = emptyList()):ItemStack=ItemStack(item).also {
        it.set(DataComponents.CUSTOM_NAME,text(title).withStyle(ChatFormatting.WHITE))
        it.set(DataComponents.LORE,ItemLore(lines.map { line -> text(line).withStyle(ChatFormatting.GRAY) }))
    }
    private fun text(value:String)=Component.literal(value.filter { !it.isISOControl() && it.code!=167 }.take(256)).withStyle { it.withItalic(false) }
}
