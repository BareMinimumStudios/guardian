package com.bareminimumstudios.guardian.platform.minecraft

import java.util.IdentityHashMap
import com.bareminimumstudios.guardian.checkpoint.*
import com.bareminimumstudios.guardian.domain.*
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/** A pinned routing view only. It grants no lease, journal or mutation authority on its own. */
internal class MinecraftPlayerEquipmentStorage private constructor(
    val player: ServerPlayer,val layout: PlayerStorageLayout,
    private val providers: MinecraftEquipmentProviders,
    val observation: MinecraftEquipmentProviders.Observation
) : Container {
    private class Route(val group: MinecraftEquipmentProviders.Group,val index: Int)
    private val thread=Thread.currentThread()
    val normal=player.inventory
    private val byName=observation.groups.flatMap { group -> (0 until group.size).map { index ->
        EquipmentSlotAddress(group.provider,"${group.name}/$index") to Route(group,index)
    } }.toMap()
    private val routes=layout.extraSlots.map { byName.getValue(it) }

    fun isCurrent(): Boolean {
        check(Thread.currentThread()===thread)
        if(player.inventory!==normal || normal.containerSize!=layout.normalSlots || observation.groups.any { !it.attached() })return false
        return providers.matches(player,observation)
    }

    fun validateTarget(image: InventorySnapshot) {
        check(isCurrent())
        val owner=ItemSlotOwner.PlayerInventory(player.uuid,layout)
        require(image.slots.keys==((0 until layout.slotCount).map { ItemSlotAddress(owner,it) }.toSet()))
        for((index,route) in routes.withIndex()) {
            val target=image.slots.getValue(ItemSlotAddress(owner,layout.normalSlots+index))
            if(!target.isEmpty)require(route.group.accepts(route.index,MinecraftItemSnapshotter.restore(target,player.registryAccess()))) {
                "${route.group.provider}/${route.group.name}/${route.index} no longer accepts its saved item; no items changed"
            }
        }
        check(isCurrent())
    }

    fun detach(): MinecraftInventoryHandoff {
        check(isCurrent())
        val frames=IdentityHashMap<MinecraftEquipmentProviders.Group,MinecraftInventoryHandoff>()
        val captured=mutableListOf<MinecraftInventoryHandoff>()
        val protection=EquipmentMutationGate.acquire(observation.groups.flatMap { it.roots }+player)
        try {
            val vanilla=MinecraftDetachedInventory.capture(normal,player.registryAccess()).also(captured::add)
            observation.groups.filter { it.size>0 }.forEach { group ->
                group.beforeDetach()
                check(group.write!=null) { "${group.provider} has no native slot writer" }
                val frame=group.single?.let { MinecraftDetachedEquipmentSlot.capture(it,player.registryAccess()) }
                    ?: MinecraftDetachedEquipmentList.capture(checkNotNull(group.backing) { "${group.provider} has no complete native handoff" },player.registryAccess())
                frames[group]=frame.also(captured::add)
            }
            check(isCurrent())
            val composite=MinecraftCompositeHandoff((0 until layout.normalSlots).map { MinecraftCompositeHandoff.Slot(vanilla,it) }+
                routes.map { MinecraftCompositeHandoff.Slot(frames.getValue(it.group),it.index) }) {
                captured.filterIsInstance<MinecraftDetachedEquipmentSlot>().forEach { it.finishRelease() }
                protection.close()
            }
            // Establish an exact, returnable original wrapper before admission can fail
            // without any item writes. The first actual write discards this preparation.
            composite.prepareRelease()
            return composite
        } catch(error: Exception) {
            captured.asReversed().forEach { frame -> if(frame.isCurrent()) { frame.prepareRelease();frame.release() } }
            captured.filterIsInstance<MinecraftDetachedEquipmentSlot>().forEach { it.finishRelease() }
            protection.close()
            throw error
        }
    }

    override fun getContainerSize()=layout.slotCount
    override fun isEmpty()=(0 until containerSize).all { getItem(it).isEmpty }
    override fun getItem(index: Int): ItemStack {
        check(Thread.currentThread()===thread);require(index in 0 until containerSize)
        return if(index<layout.normalSlots)normal.getItem(index) else routes[index-layout.normalSlots].let { it.group.read(it.index) }
    }
    override fun setItem(index: Int,stack: ItemStack) {
        check(isCurrent());require(index in 0 until containerSize)
        if(index<layout.normalSlots)normal.setItem(index,stack)
        else routes[index-layout.normalSlots].let {
            require(stack.isEmpty || it.group.accepts(it.index,stack)) { "Equipment slot no longer accepts its saved item" }
            checkNotNull(it.group.write).invoke(it.index,stack)
        }
        check(isCurrent())
    }
    override fun removeItem(index: Int,count: Int): ItemStack = error("Equipment routing view cannot extract items")
    override fun removeItemNoUpdate(index: Int): ItemStack = error("Equipment routing view cannot extract items")
    override fun clearContent(): Unit = error("Equipment routing view cannot clear items")
    override fun setChanged()=normal.setChanged()
    override fun stillValid(player: Player)=player===this.player && isCurrent()

    companion object {
        fun bind(player: ServerPlayer,layout: PlayerStorageLayout): MinecraftPlayerEquipmentStorage {
            val providers=MinecraftEquipmentProviders(EquipmentModDiscovery.installed())
            val observed=providers.observe(player)
            check(observed.image.providers.keys==layout.providers && observed.image.slots.keys==layout.extraSlots.toSet() &&
                player.inventory.containerSize==layout.normalSlots) { "Installed equipment layout differs from the protected player image" }
            check(observed.groups.filter { it.size>0 }.all { (it.backing!=null || it.single!=null) && it.write!=null }) {
                "An equipment provider still lacks complete native handoff support"
            }
            for(group in observed.groups)if(group.single!=null)check(group.identity is OwnedEquipmentProvider && group.identity is OwnedEquipmentSerialization) {
                "${group.provider} native singleton ownership or save hooks are unavailable for this API"
            }
            for(group in observed.groups)if(group.provider in setOf("trinkets","curios","toolbelt","accessories")) {
                val roots=if(group.provider in setOf("trinkets","accessories"))group.roots else group.roots.dropLast(1)
                check(roots.all { it is OwnedEquipmentProvider }) { "${group.provider} native topology hooks are unavailable for this API" }
            }
            return MinecraftPlayerEquipmentStorage(player,layout,providers,observed).also { check(it.isCurrent()) }
        }
    }
}
