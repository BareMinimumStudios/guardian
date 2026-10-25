package com.bareminimumstudios.guardian.lookup

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.*
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import java.time.Instant

/** Vanilla chest protocol on both loaders. Every slot is virtual, including the bottom inventory. */
internal class ItemTransactionMenu(
    id: Int, private val viewer: ServerPlayer, private val permitted: () -> Boolean,
    private val requestPage: (Int) -> Unit
) : AbstractContainerMenu(MenuType.GENERIC_9x6,id) {
    private val display=SimpleContainer(90)
    internal var view=ItemTransactionView(emptyList())
        private set
    private var resultPage=1
    private var previous=false
    private var next=false
    private var loading=true
    private var closed=false
    private var resync=false
    init {
        for(i in 0 until 90) addSlot(object : Slot(display,i,8+(i%9)*18,18+(i/9)*18) {
            override fun mayPlace(stack: ItemStack)=false
            override fun mayPickup(player: Player)=false
        })
        render()
    }
    fun loading() { loading=true;render() }
    fun results(rows: List<ContainerTransactionSnapshot>, page: Int, hasPrevious: Boolean, hasNext: Boolean) {
        view=ItemTransactionView(rows);resultPage=page;previous=hasPrevious;next=hasNext;loading=false;render()
    }
    override fun stillValid(player: Player)= !closed && player===viewer && permitted()
    override fun quickMoveStack(player: Player,index: Int): ItemStack=ItemStack.EMPTY
    override fun canDragTo(slot: Slot)=false
    override fun canTakeItemForPickAll(stack: ItemStack,slot: Slot)=false
    override fun getCarried(): ItemStack=ItemStack.EMPTY
    override fun setCarried(stack: ItemStack) { /* Display stacks never become a carried stack. */ }
    override fun removed(player: Player) { closed=true;view=ItemTransactionView(emptyList());display.clearContent() }
    override fun clicked(index: Int,button: Int,type: ClickType,player: Player) {
        if(player!==viewer)return
        if(!stillValid(player)) { viewer.closeContainer();return }
        resync=true
        // Never delegate to vanilla's inventory mutation machinery, even for bottom/outside slots.
        if(type!=ClickType.PICKUP || button!=0)return
        if(index==53) { viewer.closeContainer();return }
        if(loading)return
        when(index) {
            45 -> if(previous)requestPage(-1)
            46 -> view.transaction(-1)
            47 -> view.transaction(1)
            48 -> view.changes(-1)
            50 -> view.changes(1)
            51 -> if(next)requestPage(1)
        }
        render()
    }
    override fun broadcastChanges() {
        if(viewer.containerMenu===this && !stillValid(viewer)) { viewer.closeContainer();return }
        // Copies only; no Slot ever points at the viewer's real inventory.
        for(i in 0 until 36)display.setItem(54+i,viewer.inventory.getItem(if(i<27)i+9 else i-27).copy())
        // The packet handler installs client predictions after clicked(). Correct them afterwards.
        if(resync) { resync=false;super.broadcastFullState() } else super.broadcastChanges()
    }
    private fun render() {
        for(i in 0 until 54)display.setItem(i,card(Items.GRAY_STAINED_GLASS_PANE," "))
        display.setItem(2,card(Items.RED_STAINED_GLASS_PANE,"Before",listOf("Recorded changed slots only")))
        display.setItem(6,card(Items.LIME_STAINED_GLASS_PANE,"After",listOf("Recorded changed slots only")))
        display.setItem(53,card(Items.BARRIER,"Close",listOf("All slots, including your inventory, are read-only")))
        val row=view.current
        val actor=when(val a=row?.actor) { is ActorIdentity.Player -> a.lastKnownName ?: a.uuid.toString();is ActorIdentity.System -> a.source;else -> "Unknown" }
        val details=if(row==null)listOf(if(loading)"Loading history..." else "No transactions on this result page") else listOf(
            "Player/system: $actor","Time: ${Instant.ofEpochMilli(row.timestampEpochMillis)}",
            "Action: ${row.action.name.lowercase().replace('_',' ')}","ID: ${row.transactionId}",
            "${row.changes.size} changed slots; not a complete inventory","Historical components are not executed or sent to clients")
        display.setItem(4,card(Items.BOOK,if(loading)"Loading item history" else "Transaction ${if(row==null)0 else view.transaction+1}/${view.rows.size}",details))
        view.changes().forEachIndexed { i,change ->
            val left=9+(i/4)*9+i%4
            val location=owner(change.address.owner)+" | slot ${change.address.index}"
            val changed=change.before.itemId==change.after.itemId && change.before.itemData!=change.after.itemData
            display.setItem(left,item(change.before,"Before",location,changed))
            display.setItem(left+5,item(change.after,"After",location,changed))
        }
        display.setItem(45,card(if(previous)Items.ARROW else Items.GRAY_DYE,"Previous results",listOf("Search result page ${resultPage-1}")))
        display.setItem(46,card(if(view.transaction>0)Items.ARROW else Items.GRAY_DYE,"Previous transaction"))
        display.setItem(47,card(if(view.transaction+1<view.rows.size)Items.ARROW else Items.GRAY_DYE,"Next transaction"))
        display.setItem(48,card(if(view.changePage>0)Items.ARROW else Items.GRAY_DYE,"Previous changed slots"))
        display.setItem(49,card(Items.PAPER,"Results $resultPage | slots ${if(row==null)0 else view.changePage+1}/${view.changePages}",listOf("Left-click buttons to navigate","Hover items for recorded counts, IDs and ownership","Read-only; close to use your inventory")))
        display.setItem(50,card(if(view.changePage+1<view.changePages)Items.ARROW else Items.GRAY_DYE,"Next changed slots"))
        display.setItem(51,card(if(next)Items.ARROW else Items.GRAY_DYE,"Next results",listOf("Search result page ${resultPage+1}")))
        resync=true
    }
    private fun item(snapshot: ItemStackSnapshot,side: String,location: String,componentsChanged: Boolean): ItemStack {
        val id=snapshot.itemId
        // Plain vanilla icons only. Never materialize a recorded mod component codec or nested item data.
        val icon=if(id?.namespace=="minecraft")BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id.toString())).orElse(Items.PAPER) else Items.PAPER
        return card(if(snapshot.isEmpty)Items.LIGHT_GRAY_STAINED_GLASS_PANE else icon,
            "$side: ${if(snapshot.isEmpty)"empty" else "${snapshot.count} ${id!!.path.replace('_',' ')}"}",
            listOf(location,"Recorded item: ${id ?: "empty"}","Recorded count: ${snapshot.count}",
                if(componentsChanged)"Saved components changed in this slot" else "Saved components retained in history",
                "Plain icon; historical name/lore/components are not rendered"))
    }
    private fun owner(owner: ItemSlotOwner)=when(owner) {
        is ItemSlotOwner.BlockContainer -> "${owner.dimension} @ ${owner.position.x}, ${owner.position.y}, ${owner.position.z}"
        is ItemSlotOwner.PlayerInventory -> "Player inventory ${owner.playerId}"
        is ItemSlotOwner.Cursor -> "Player cursor ${owner.playerId}"
        is ItemSlotOwner.CraftingGrid -> "Crafting grid ${owner.playerId}"
    }
    private fun card(item: Item,title: String,lines: List<String> = emptyList()): ItemStack=ItemStack(item).also { stack ->
        stack.set(DataComponents.CUSTOM_NAME,text(title).withStyle(ChatFormatting.WHITE))
        stack.set(DataComponents.LORE,ItemLore(lines.map { text(it).withStyle(ChatFormatting.GRAY) }))
    }
    private fun text(value: String)=Component.literal(value.filter { !it.isISOControl() && it!='§' }.take(256)).withStyle { it.withItalic(false) }
}
