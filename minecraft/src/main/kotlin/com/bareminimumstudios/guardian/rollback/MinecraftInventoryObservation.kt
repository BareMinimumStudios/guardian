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
class MinecraftInventoryObservation private constructor(val snapshot: InventorySnapshot, val isCurrent: () -> Boolean) {
    companion object {
        fun read(server: MinecraftServer,owner: ItemSlotOwner,wanted: Set<ItemSlotAddress>): MinecraftInventoryObservation? {
            check(server.isSameThread)
            return when(owner) {
                is ItemSlotOwner.BlockContainer -> {
                    val level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(owner.dimension.toString()))) ?: return null
                    val container=MinecraftBlockContainerSnapshotter.resolve(level,BlockPos(owner.position.x,owner.position.y,owner.position.z)) ?: return null
                    val snapshot=MinecraftBlockContainerSnapshotter.captureSelected(container,level,wanted) ?: return null
                    val identities=parts(container).map { entity ->
                        val state=entity.blockState;val size=(entity as Container).containerSize;
                        { loaded(level,entity.blockPos) && !entity.isRemoved && level.getBlockEntity(entity.blockPos) === entity && entity.blockState==state && (entity as Container).containerSize==size && (entity !is RandomizableContainer || entity.lootTable==null) }
                    }
                    MinecraftInventoryObservation(snapshot) { identities.all { it() } }
                }
                is ItemSlotOwner.PlayerInventory -> {
                    val player=server.playerList.getPlayer(owner.playerId) ?: return null
                    val menu=player.inventoryMenu;val inventory=player.inventory;val level=player.level()
                    fun current()=server.playerList.getPlayer(owner.playerId) === player && player.level() === level &&
                        player.server === server && !player.isRemoved && player.inventory === inventory &&
                        inventory.javaClass == net.minecraft.world.entity.player.Inventory::class.java &&
                        inventory.player === player && inventory.containerSize == 41 &&
                        player.inventoryMenu === menu && MinecraftPlayerInventoryEligibility.isIdle(player)
                    if(!current()) return null
                    val slots=linkedMapOf<ItemSlotAddress,ItemStackSnapshot>()
                    val items=MinecraftItemSnapshotter.CaptureBatch(player.registryAccess());var bytes=0L
                    wanted.filter { it.owner==owner && it.index in 0 until inventory.containerSize }.forEach { address ->
                        val item=items.capture(inventory.getItem(address.index));bytes+=(item.itemData?.size ?: 0)
                        require(bytes<=16L*1024*1024) { "Observation payload budget exceeded" };slots[address]=item
                    }
                    MinecraftInventoryObservation(InventorySnapshot(slots),::current)
                }
                else -> null
            }
        }
        private fun loaded(level: ServerLevel,pos: BlockPos)=level.chunkSource.getChunkNow(pos.x shr 4,pos.z shr 4)!=null
        private fun parts(container: Container): List<BlockEntity> = if(container is CompoundContainer) {
            val accessor=container as CompoundContainerAccessor
            parts(accessor.`guardian$first`())+parts(accessor.`guardian$second`())
        } else listOf(container as BlockEntity)
    }
}
