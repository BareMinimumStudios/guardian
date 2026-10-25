package com.bareminimumstudios.guardian.rollback

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.platform.minecraft.*
import com.bareminimumstudios.guardian.mixin.CompoundContainerAccessor
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.CompoundContainer
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.level.block.entity.BlockEntity

/** Live object/state identity check only; it does not freeze contents or certify a disk save. */
class MinecraftInventoryObservation private constructor(val snapshot: InventorySnapshot, val failureReason: () -> String?) {
    val isCurrent: () -> Boolean = { failureReason()==null }
    companion object {
        fun read(server: MinecraftServer,owner: ItemSlotOwner,wanted: Set<ItemSlotAddress>): MinecraftInventoryObservation? {
            return read(server,owner,wanted) {}
        }
        fun read(server: MinecraftServer,owner: ItemSlotOwner,wanted: Set<ItemSlotAddress>,unavailable:(String)->Unit): MinecraftInventoryObservation? {
            check(server.isSameThread)
            fun reject(reason:String):MinecraftInventoryObservation? { unavailable(reason);return null }
            return when(owner) {
                is ItemSlotOwner.BlockContainer -> {
                    val level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(owner.dimension.toString()))) ?: return reject("dimension is not available")
                    val pos=BlockPos(owner.position.x,owner.position.y,owner.position.z)
                    if(!loaded(level,pos)) return reject("container chunk is not loaded")
                    val entity=level.getBlockEntity(pos) ?: return reject("no block entity exists at this position")
                    if(entity !is Container) return reject("block entity is not an inventory ("+entity.javaClass.name+")")
                    val state=level.getBlockState(pos)
                    if(state.block is net.minecraft.world.level.block.ChestBlock && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE && !loaded(level,pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state)))) return reject("other half of the double chest is not loaded")
                    val container=MinecraftBlockContainerSnapshotter.resolve(level,pos) ?: return reject("container could not be resolved")
                    for(part in parts(container)) {
                        if(part.level !== level) return reject("container belongs to a different level")
                        if(part.isRemoved) return reject("container block entity is removed")
                        if(!loaded(level,part.blockPos)) return reject("container chunk is not loaded")
                        if(level.getBlockEntity(part.blockPos)!==part) return reject("container block entity was replaced")
                        if(part is RandomizableContainer && part.lootTable!=null) return reject("container has unopened generated loot; reading it would generate loot")
                    }
                    if(container.containerSize !in 1..com.bareminimumstudios.guardian.storage.codec.ContainerChangesCodec.MAX_SLOTS) return reject("unsupported container slot count ("+container.containerSize+")")
                    val snapshot=MinecraftBlockContainerSnapshotter.captureSelected(container,level,wanted) ?: return reject("container snapshot is unavailable")
                    val identities=parts(container).map { entity ->
                        val state=entity.blockState;val size=(entity as Container).containerSize;
                        {
                            when {
                                !loaded(level,entity.blockPos)->"container chunk unloaded during checking"
                                entity.isRemoved->"container block entity was removed during checking"
                                level.getBlockEntity(entity.blockPos)!==entity->"container block entity was replaced during checking"
                                entity.blockState!=state->"container block state changed during checking"
                                (entity as Container).containerSize!=size->"container slot count changed during checking"
                                entity is RandomizableContainer && entity.lootTable!=null->"container became sealed loot during checking"
                                else->null
                            }
                        }
                    }
                    MinecraftInventoryObservation(snapshot) { identities.firstNotNullOfOrNull { it() } }
                }
                is ItemSlotOwner.PlayerInventory -> {
                    val player=server.playerList.getPlayer(owner.playerId) ?: return reject("player is offline or no longer registered on this server")
                    val menu=player.inventoryMenu;val inventory=player.inventory;val level=player.level()
                    fun current():String? = when {
                        server.playerList.getPlayer(owner.playerId)!==player->"player connection/identity changed during checking"
                        player.level()!==level->"player level changed during checking"
                        player.server!==server->"player belongs to a different server"
                        player.isRemoved->"player entity is removed"
                        player.inventory!==inventory->"player inventory object was replaced"
                        inventory.javaClass!=net.minecraft.world.entity.player.Inventory::class.java->"unsupported player inventory implementation ("+inventory.javaClass.name+")"
                        inventory.player!==player->"inventory is attached to a different player"
                        inventory.containerSize!=41->"unsupported player inventory size ("+inventory.containerSize+"; expected 41)"
                        player.inventoryMenu!==menu->"personal inventory menu was replaced"
                        else->MinecraftPlayerInventoryEligibility.failureReason(player)
                    }
                    current()?.let { return reject(it) }
                    val slots=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>()
                    val items=MinecraftItemSnapshotter.CaptureBatch(player.registryAccess());var bytes=0L
                    wanted.filter { it.owner==owner && it.index in 0 until inventory.containerSize }.forEach { address ->
                        val item=try { items.capture(inventory.getItem(address.index)) } catch(error:Exception) { unavailable("player inventory slot "+address.index+" cannot be read ("+error.javaClass.simpleName+")");throw error };bytes+=(item.itemData?.size ?: 0)
                        require(bytes<=16L*1024*1024) { "Observation payload budget exceeded" };slots[address]=item
                    }
                    MinecraftInventoryObservation(InventorySnapshot(slots),::current)
                }
                else -> reject("temporary or unsupported inventory ownership")
            }
        }
        private fun loaded(level: ServerLevel,pos: BlockPos)=level.chunkSource.getChunkNow(pos.x shr 4,pos.z shr 4)!=null
        private fun parts(container: Container): List<BlockEntity> = if(container is CompoundContainer) {
            val accessor=container as CompoundContainerAccessor
            parts(accessor.`guardian$first`())+parts(accessor.`guardian$second`())
        } else listOf(container as BlockEntity)
    }
}
